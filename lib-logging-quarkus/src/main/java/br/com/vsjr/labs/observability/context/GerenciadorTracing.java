package br.com.vsjr.labs.observability.context;

import java.util.Comparator;

import br.com.vsjr.labs.observability.context.enriquecedor.tracing.EnriquecedorTracing;
import org.jboss.logging.MDC;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;

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
 * <p>Separado do {@link br.com.vsjr.labs.observability.context.GerenciadorContextoLog}
 * para manter a separação de responsabilidades: este bean cuida do ciclo de
 * vida do span OTel; o outro cuida do MDC de correlação da requisição HTTP.</p>
 *
 * <p>O {@link Tracer} é obtido a partir do bean CDI {@code OpenTelemetry},
 * fornecido pelo {@code quarkus-opentelemetry} — o {@code Tracer} em si
 * não é um bean CDI direto.</p>
 *
 * <p>O pipeline de enriquecimento executa cada {@link EnriquecedorTracing} em
 * ordem crescente de {@link EnriquecedorTracing#prioridade()}.
 * Todos os enriquecedores são executados (sem short-circuit funcional).
 * Novos enriquecedores são descobertos automaticamente pelo CDI — sem
 * alteração nesta classe.</p>
 */
@ApplicationScoped
public class GerenciadorTracing {

    Tracer tracer;
    Instance<EnriquecedorTracing> enriquecedores;

    private static final String CAMPO_TRACE_ID = "traceId";
    private static final String CAMPO_SPAN_ID = "spanId";

    public GerenciadorTracing(OpenTelemetry openTelemetry, Instance<EnriquecedorTracing> enriquecedores) {
        this.tracer = openTelemetry.getTracer(GerenciadorTracing.class.getName());
        this.enriquecedores = enriquecedores;
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
                .sorted(Comparator.comparingInt(EnriquecedorTracing::prioridade))
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
