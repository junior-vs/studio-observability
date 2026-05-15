package br.com.vsjr.labs.observability.interceptor;

import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.annotations.Traced;
import br.com.vsjr.labs.observability.tracing.GerenciadorTracing;
import br.com.vsjr.labs.observability.tracing.enriquecedor.EnriquecedorTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingInterceptorTest {

    @AfterEach
    void limparMdc() {
        MDC.remove(CamposMdc.SPAN_ID.chave());
    }

    @Test
    void interceptor_deve_usar_traced_e_executar_antes_do_log_interceptor() {
        assertTrue(TracingInterceptor.class.isAnnotationPresent(Traced.class));

        var prioridadeTracing = TracingInterceptor.class.getAnnotation(Priority.class).value();
        var prioridadeLogging = LogInterceptor.class.getAnnotation(Priority.class).value();

        assertTrue(prioridadeTracing < prioridadeLogging);
    }

    @Test
    void rastrear_deve_restaurar_span_id_pai_apos_sucesso() throws Exception {
        MDC.put(CamposMdc.SPAN_ID.chave(), "span-pai");
        var gerenciador = new GerenciadorTracingFake(false);
        var interceptor = new TracingInterceptor(gerenciador);
        var spanDuranteExecucao = new AtomicReference<>();

        var resultado = interceptor.rastrear(new InvocationContextFake(() -> {
            spanDuranteExecucao.set(MDC.get(CamposMdc.SPAN_ID.chave()));
            return "ok";
        }));

        assertEquals("ok", resultado);
        assertEquals("span-filho", spanDuranteExecucao.get());
        assertEquals("span-pai", MDC.get(CamposMdc.SPAN_ID.chave()));
        assertEquals("span-pai", gerenciador.spanIdPaiRecebido);
        assertTrue(gerenciador.iniciado);
        assertTrue(gerenciador.encerrado);
    }

    @Test
    void rastrear_deve_marcar_erro_e_relancar_excecao_original() {
        MDC.put(CamposMdc.SPAN_ID.chave(), "span-pai");
        var erroNegocio = new Exception("falha de negocio");
        var gerenciador = new GerenciadorTracingFake(false);
        var interceptor = new TracingInterceptor(gerenciador);

        var erro = assertThrows(Exception.class,
                () -> interceptor.rastrear(new InvocationContextFake(() -> {
                    throw erroNegocio;
                })));

        assertSame(erroNegocio, erro);
        assertSame(erroNegocio, gerenciador.erroMarcado);
        assertEquals("span-pai", MDC.get(CamposMdc.SPAN_ID.chave()));
    }

    @Test
    void rastrear_nao_deve_substituir_excecao_de_negocio_quando_encerrar_span_falhar() {
        var erroNegocio = new Exception("falha de negocio");
        var gerenciador = new GerenciadorTracingFake(true);
        var interceptor = new TracingInterceptor(gerenciador);

        var erro = assertThrows(Exception.class,
                () -> interceptor.rastrear(new InvocationContextFake(() -> {
                    throw erroNegocio;
                })));

        assertSame(erroNegocio, erro);
        assertSame(erroNegocio, gerenciador.erroMarcado);
    }

    @Test
    void gerenciador_deve_executar_enriquecedores_por_prioridade() throws Exception {
        var ordem = new ArrayList<String>();
        var gerenciador = new GerenciadorTracing(
                OpenTelemetry.noop(),
                instanceOf(List.of(
                        enriquecedor("segundo", 20, ordem),
                        enriquecedor("primeiro", 10, ordem)
                )));

        var contextoSpan = gerenciador.iniciar("Teste.operacao", new InvocationContextFake(() -> null));
        gerenciador.encerrar(contextoSpan, null);

        assertEquals(List.of("primeiro", "segundo"), ordem);
    }

    private static EnriquecedorTracing enriquecedor(String nome, int prioridade, List<String> ordem) {
        return new EnriquecedorTracing() {
            @Override
            public void enriquecer(Span span, InvocationContext contexto) {
                ordem.add(nome);
            }

            @Override
            public int prioridade() {
                return prioridade;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Instance<EnriquecedorTracing> instanceOf(List<EnriquecedorTracing> enriquecedores) {
        return (Instance<EnriquecedorTracing>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(),
                new Class<?>[]{Instance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stream" -> enriquecedores.stream();
                    case "iterator" -> enriquecedores.iterator();
                    case "isUnsatisfied" -> enriquecedores.isEmpty();
                    case "isResolvable" -> !enriquecedores.isEmpty();
                    case "isAmbiguous" -> false;
                    case "get" -> enriquecedores.isEmpty() ? null : enriquecedores.getFirst();
                    case "select" -> proxy;
                    case "destroy" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class GerenciadorTracingFake extends GerenciadorTracing {
        private final boolean falharEncerramento;
        private final ContextoSpan contextoSpan = new ContextoSpan(spanProxy(), () -> {
        });
        private boolean iniciado;
        private boolean encerrado;
        private String spanIdPaiRecebido;
        private Throwable erroMarcado;

        private GerenciadorTracingFake(boolean falharEncerramento) {
            super(OpenTelemetry.noop(), instanceOf(List.of()));
            this.falharEncerramento = falharEncerramento;
        }

        @Override
        public ContextoSpan iniciar(String nomeSpan, InvocationContext contexto) {
            iniciado = true;
            MDC.put(CamposMdc.SPAN_ID.chave(), "span-filho");
            return contextoSpan;
        }

        @Override
        public void marcarErro(ContextoSpan ctx, Throwable causa) {
            erroMarcado = causa;
        }

        @Override
        public void encerrar(ContextoSpan ctx, String spanIdPai) {
            spanIdPaiRecebido = spanIdPai;
            if (falharEncerramento) {
                throw new IllegalStateException("falha otel");
            }
            encerrado = true;
            if (spanIdPai != null) {
                MDC.put(CamposMdc.SPAN_ID.chave(), spanIdPai);
            } else {
                MDC.remove(CamposMdc.SPAN_ID.chave());
            }
        }
    }

    private static Span spanProxy() {
        return (Span) Proxy.newProxyInstance(
                Span.class.getClassLoader(),
                new Class<?>[]{Span.class},
                (proxy, method, args) -> method.getReturnType().isInstance(proxy) ? proxy : null);
    }

    private static final class InvocationContextFake implements InvocationContext {
        private final Callable<Object> acao;

        private InvocationContextFake(Callable<Object> acao) {
            this.acao = acao;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Method getMethod() {
            try {
                return TracingInterceptorTest.class.getDeclaredMethod("metodoAlvo");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                        "Falha ao resolver metodo de teste 'metodoAlvo()' em TracingInterceptorTest. "
                                + "Verifique se o metodo auxiliar ainda existe com assinatura sem parametros.",
                        e);
            }
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public void setParameters(Object[] params) {
        }

        @Override
        public Map<String, Object> getContextData() {
            return Map.of();
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Object proceed() throws Exception {
            return acao.call();
        }
    }

    @SuppressWarnings("unused")
    private static void metodoAlvo() {
    }
}
