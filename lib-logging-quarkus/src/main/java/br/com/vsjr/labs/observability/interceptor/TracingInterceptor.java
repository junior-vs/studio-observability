package br.com.vsjr.labs.observability.interceptor;

import br.com.vsjr.labs.observability.dsl.enums.EventError;
import org.jboss.logging.MDC;

import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.annotations.Traced;
import br.com.vsjr.labs.observability.dsl.Log;
import br.com.vsjr.labs.observability.dsl.enums.EntrypointEnum;
import br.com.vsjr.labs.observability.security.LocalizacaoMetodo;
import br.com.vsjr.labs.observability.tracing.GerenciadorTracing;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI Interceptor ativado por {@link Traced}.
 *
 * <p>Para cada método interceptado:</p>
 * <ol>
 *   <li>Captura o {@code spanId} do span pai do MDC</li>
 *   <li>Cria um Child Span OTel e atualiza o MDC com o novo {@code spanId}</li>
 *   <li>Executa o método — o {@link LogInterceptor} registra a localização neste ponto</li>
 *   <li>Em caso de exceção, marca o span como {@code ERROR}</li>
 *   <li>No {@code finally}: encerra o span e restaura o {@code spanId} do pai no MDC</li>
 * </ol>
 *
 * <p><b>Sobre a prioridade:</b> {@code APPLICATION - 10} garante execução antes do
 * {@link LogInterceptor} ({@code APPLICATION}). Isso faz com que o {@code spanId}
 * do filho já esteja no MDC quando o {@link LogInterceptor} registrar a localização
 * técnica do método.</p>
 *
 * <p><b>Sobre o beans.xml:</b> não necessário — o ArC descobre e ativa este interceptor
 * automaticamente via índice Jandex durante o build do Quarkus.</p>
 */
@Traced
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 10)
public class TracingInterceptor {


    private final GerenciadorTracing gerenciador;

    public TracingInterceptor(GerenciadorTracing gerenciador) {
        this.gerenciador = gerenciador;
    }


    /**
     * Intercepta o método anotado com {@link Traced} e gerencia o ciclo de vida do span.
     *
     * @param contexto contexto CDI da invocação
     * @return resultado do método interceptado
     * @throws Exception qualquer exceção lançada pelo método interceptado
     */
    @AroundInvoke
    public Object rastrear(InvocationContext contexto) throws Exception {
        var localizacao = LocalizacaoMetodo.extrair(contexto);
        var nomeSpan = localizacao.operacao();

        // Salva o spanId do pai antes de criar o Child Span para restaurar no finally
        var spanIdPai = (String) MDC.get(CamposMdc.SPAN_ID.chave());

        GerenciadorTracing.ContextoSpan contextoSpan = null;
        try {
            contextoSpan = gerenciador.iniciar(nomeSpan, contexto);
            return contexto.proceed();
        } catch (Throwable erro) {
            if (contextoSpan != null) {
                try {
                    gerenciador.marcarErro(contextoSpan, erro);
                } catch (RuntimeException | Error falhaOtel) {
                    registrarFalhaOtel("marcar erro no span OTel", falhaOtel);
                }
            }
            relancar(erro);
            return null; // inalcançável; necessário para análise de fluxo do compilador
        } finally {
            if (contextoSpan != null) {
                try {
                    gerenciador.encerrar(contextoSpan, spanIdPai);
                } catch (RuntimeException | Error otelEx) {
                    registrarFalhaOtel("encerramento de span OTel", otelEx);
                }
            }
        }
    }

    private static void relancar(Throwable erro) throws Exception {
        if (erro instanceof Exception excecao) {
            throw excecao;
        }
        if (erro instanceof Error falhaGrave) {
            throw falhaGrave;
        }
        throw new RuntimeException(erro);
    }

    private static void registrarFalhaOtel(String operacao, Throwable causa) {
        Log.registrando(EventError.EVENT_ERROR)
                .em(TracingInterceptor.class, "rastrear")
                .porque("Falha ao " + operacao)
                .como(EntrypointEnum.INTERNO)
                .erro(causa);
    }
}
