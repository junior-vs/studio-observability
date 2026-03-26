package br.com.vsjr.labs.log.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Comparator;

/**
 * Gerencia o ciclo de vida de spans customizados para métodos de negócio.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Criar Child Spans a partir do contexto OTel ativo</li>
 *   <li>Atualizar o MDC com o {@code spanId} do filho durante a execução</li>
 *   <li>Encerrar spans e restaurar o MDC ao estado anterior</li>
 *   <li>Marcar spans como {@code ERROR} em caso de exceção</li>
 * </ul>
 *
 * <p>Separado do {@link br.com.vsjr.labs.log.context.GerenciadorContextoLog}
 * para manter a separação de responsabilidades: este bean cuida do ciclo de
 * vida do span OTel; o outro cuida do MDC de correlação da requisição HTTP.</p>
 *
 * <p>O {@link Tracer} é injetado pelo CDI via {@code quarkus-opentelemetry} —
 * sem configuração adicional além da já presente no {@code application.properties}.</p>
 *
 * <p>O pipeline de enriquecimento executa cada {@link EnriquecedorSpan} em
 * ordem crescente de {@link EnriquecedorSpan#prioridade()}.
 * Todos os enriquecedores são executados (sem short-circuit funcional).
 * Novos enriquecedores são descobertos automaticamente pelo CDI — sem
 * alteração nesta classe.</p>
 */
@ApplicationScoped
public class GerenciadorRastreamento {

    Tracer tracer;

    @Inject
    Instance<EnriquecedorSpan> enriquecedores;

    private static final String CAMPO_TRACE_ID = "traceId";
    private static final String CAMPO_SPAN_ID = "spanId";

    public GerenciadorRastreamento(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Sincroniza o MDC com {@code traceId} e {@code spanId} do span OTel ativo.
     *
     * <p>Deve ser chamado uma vez por requisição HTTP, no filtro de entrada,
     * após a instrumentação automática do Quarkus ter associado o span raiz.
     * Se não houver span ativo (ex: jobs sem instrumentação), nenhum campo
     * é inserido — evitando valores inválidos.</p>
     *
     * <p>Centraliza aqui toda a escrita de campos de rastreamento no MDC,
     * mantendo o {@code GerenciadorContextoLog} livre de dependências OTel.</p>
     */
    public void sincronizarMdcRequisicao() {
        var spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            MDC.put(CAMPO_TRACE_ID, spanContext.getTraceId());
            MDC.put(CAMPO_SPAN_ID, spanContext.getSpanId());
        }
    }

    /**
     * Cria um Child Span para o método interceptado e atualiza o MDC.
     *
     * <p>O span é criado como filho do span ativo em {@code Context.current()} —
     * nunca como root span. O MDC é atualizado com o {@code spanId} do filho,
     * garantindo que os logs emitidos durante a execução do método referenciem
     * o span correto no Jaeger/Grafana Tempo.</p>
     *
     * @param nomeSpan nome do span no formato {@code "Classe.metodo"}
     * @param contexto contexto CDI da invocação; repassado para o pipeline de enriquecimento
     * @return contexto do span criado; deve ser passado para {@link #encerrar}
     */
    public ContextoSpan iniciar(String nomeSpan, InvocationContext contexto) {
        var span = tracer.spanBuilder(nomeSpan)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        var scope = span.makeCurrent();
        MDC.put(CAMPO_SPAN_ID, span.getSpanContext().getSpanId());

        enriquecedores.stream()
                .sorted(Comparator.comparingInt(EnriquecedorSpan::prioridade))
                .forEach(e -> e.enriquecer(span, contexto));

        return new ContextoSpan(span, scope);
    }

    /**
     * Encerra o span e restaura o MDC ao estado anterior.
     *
     * <p>A ordem de encerramento é obrigatória: Scope antes do Span.
     * Deve ser chamado em bloco {@code finally} para garantir que o
     * pipeline OTel não acumule contextos abertos.</p>
     *
     * @param ctx       contexto retornado por {@link #iniciar}
     * @param spanIdPai spanId do span pai, capturado antes de chamar {@link #iniciar};
     *                  {@code null} quando não havia span ativo
     */
    public void encerrar(ContextoSpan ctx, String spanIdPai) {
        ctx.scope().close();
        ctx.span().end();

        if (spanIdPai != null) {
            MDC.put(CAMPO_SPAN_ID, spanIdPai);
        } else {
            MDC.remove(CAMPO_SPAN_ID);
        }
    }

    /**
     * Marca o span como falha e registra a exceção como evento do span.
     *
     * <p>O status {@code ERROR} torna o span visível como falha no Jaeger/Grafana Tempo.
     * A chamada a {@link #encerrar} ainda é necessária após este método.</p>
     *
     * @param ctx   contexto do span ativo
     * @param causa exceção que causou a falha
     */
    public void marcarErro(ContextoSpan ctx, Throwable causa) {
        ctx.span().setStatus(StatusCode.ERROR, causa.getMessage());
        ctx.span().recordException(causa);
    }

    /**
     * Par imutável de {@link Span} e {@link Scope} criados em {@link #iniciar}.
     *
     * <p>Ambos precisam ser encerrados na ordem correta: Scope primeiro, Span depois.
     * A ordem é preservada pelo método {@link #encerrar}.</p>
     */
    public record ContextoSpan(Span span, Scope scope) {
    }
}
