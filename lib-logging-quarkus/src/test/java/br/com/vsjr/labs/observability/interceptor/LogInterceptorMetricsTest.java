package br.com.vsjr.labs.observability.interceptor;

import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.context.GerenciadorContextoLog;
import br.com.vsjr.labs.observability.context.enriquecedor.EnriquecedorContexto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogInterceptorMetricsTest {

    private static final String APPLICATION_NAME = "pedido-service";

    @Test
    void metodo_bem_sucedido_deve_registrar_timer_com_prefixo_da_aplicacao() throws Exception {
        var registry = new SimpleMeterRegistry();
        var interceptor = criarInterceptor(registry);

        var resultado = interceptor.interceptar(new InvocationContextFake("metodoSucesso", () -> "ok"));

        var timer = registry.find(APPLICATION_NAME + ".metodo.execucao")
                .tags(CamposMdc.CLASSE.chave(), "LogInterceptorMetricsTest",
                        CamposMdc.METODO.chave(), "metodoSucesso")
                .timer();

        assertEquals("ok", resultado);
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void metodo_com_falha_deve_incrementar_counter_por_tipo_de_excecao() {
        var registry = new SimpleMeterRegistry();
        var interceptor = criarInterceptor(registry);
        var falha = new IllegalStateException("falha de negocio");

        var erro = assertThrows(IllegalStateException.class,
                () -> interceptor.interceptar(new InvocationContextFake("metodoFalha", () -> {
                    throw falha;
                })));

        var counter = registry.find(APPLICATION_NAME + ".metodo.falha")
                .tags(CamposMdc.CLASSE.chave(), "LogInterceptorMetricsTest",
                        CamposMdc.METODO.chave(), "metodoFalha",
                        CamposMdc.EXCECAO.chave(), "IllegalStateException")
                .counter();

        assertSame(falha, erro);
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void metodo_de_negocio_deve_retornar_normalmente_sem_meter_registry() {
        var interceptor = criarInterceptor(null);

        assertDoesNotThrow(() -> {
            var resultado = interceptor.interceptar(new InvocationContextFake("metodoSucesso", () -> "ok"));
            assertEquals("ok", resultado);
        });
    }

    @Test
    void falha_de_micrometer_nao_deve_alterar_retorno_do_metodo() {
        var interceptor = criarInterceptor(new MeterRegistryFalho());

        assertDoesNotThrow(() -> {
            var resultado = interceptor.interceptar(new InvocationContextFake("metodoSucesso", () -> "ok"));
            assertEquals("ok", resultado);
        });
    }

    @Test
    void falha_de_micrometer_nao_deve_substituir_excecao_de_negocio() {
        var interceptor = criarInterceptor(new MeterRegistryFalho());
        var falha = new IllegalArgumentException("falha de negocio");

        var erro = assertThrows(IllegalArgumentException.class,
                () -> interceptor.interceptar(new InvocationContextFake("metodoFalha", () -> {
                    throw falha;
                })));

        assertSame(falha, erro);
    }

    @Test
    void metricas_automaticas_devem_usar_apenas_tags_de_baixa_cardinalidade() {
        var registry = new SimpleMeterRegistry();
        var interceptor = criarInterceptor(registry);
        var tagsAltaCardinalidade = Set.of(
                CamposMdc.USER_ID.chave(),
                CamposMdc.TRACE_ID.chave(),
                CamposMdc.SPAN_ID.chave(),
                "pedidoId");

        assertDoesNotThrow(() -> interceptor.interceptar(new InvocationContextFake("metodoSucesso", () -> "ok")));
        assertThrows(IllegalStateException.class,
                () -> interceptor.interceptar(new InvocationContextFake("metodoFalha", () -> {
                    throw new IllegalStateException("falha");
                })));

        registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .forEach(tagKey -> assertFalse(tagsAltaCardinalidade.contains(tagKey)));
    }

    private static LogInterceptor criarInterceptor(MeterRegistry meterRegistry) {
        return new LogInterceptor(
                APPLICATION_NAME,
                new GerenciadorContextoLog(APPLICATION_NAME, instanceOf(List.of())),
                meterRegistry != null ? instanceOf(List.of(meterRegistry)) : instanceOf(List.of()));
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instanceOf(List<T> beans) {
        return (Instance<T>) Proxy.newProxyInstance(
                Instance.class.getClassLoader(),
                new Class<?>[]{Instance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stream" -> beans.stream();
                    case "iterator" -> beans.iterator();
                    case "isUnsatisfied" -> beans.isEmpty();
                    case "isResolvable" -> !beans.isEmpty();
                    case "isAmbiguous" -> false;
                    case "get" -> beans.isEmpty() ? null : beans.getFirst();
                    case "select" -> proxy;
                    case "destroy" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class MeterRegistryFalho extends SimpleMeterRegistry {
        @Override
        protected Counter newCounter(Meter.Id id) {
            throw new IllegalStateException("falha micrometer");
        }

        @Override
        protected Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig, PauseDetector pauseDetector) {
            throw new IllegalStateException("falha micrometer");
        }
    }

    private static final class InvocationContextFake implements InvocationContext {
        private final String metodo;
        private final Callable<Object> acao;

        private InvocationContextFake(String metodo, Callable<Object> acao) {
            this.metodo = metodo;
            this.acao = acao;
        }

        @Override
        public Object getTarget() {
            return this;
        }

        @Override
        public Method getMethod() {
            try {
                return LogInterceptorMetricsTest.class.getDeclaredMethod(metodo);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
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
    private static void metodoSucesso() {
    }

    @SuppressWarnings("unused")
    private static void metodoFalha() {
    }
}
