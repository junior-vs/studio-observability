package br.com.vsjr.labs.log.context;


import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Gerencia o ciclo de vida do MDC para cada execução rastreável.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Capturar {@code traceId} e {@code spanId} do span OTel ativo</li>
 *   <li>Propagar {@code userId} e nome do serviço para o MDC</li>
 *   <li>Garantir limpeza segura após execução</li>
 *   <li>Produzir {@link LogContexto} inspecionável (útil em testes)</li>
 * </ul>
 *
 * <p>Usa {@code org.jboss.logging.MDC} — implementação nativa do Quarkus.
 * O SLF4J delega para JBoss Logging internamente de qualquer forma; usar
 * a API nativa elimina uma camada de indireção desnecessária.</p>
 */
@ApplicationScoped
public class GerenciadorContextoLog {

    // Nome do serviço injetado do application.properties
    @Inject
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "servico-desconhecido")
    String nomeServico;

    private static final String CAMPO_TRACE_ID = "traceId";
    private static final String CAMPO_SPAN_ID = "spanId";
    private static final String CAMPO_USER_ID = "userId";
    private static final String CAMPO_SERVICO = "servico";
    private static final String CAMPO_CLASSE = "classe";
    private static final String CAMPO_METODO = "metodo";

    /**
     * Inicializa o MDC com o contexto de correlação da requisição atual.
     *
     * <p>Captura o {@code traceId} e {@code spanId} do span OTel ativo.
     * Se não houver span ativo (ex: jobs agendados sem instrumentação),
     * os campos de trace não são inseridos — evitando valores inválidos.</p>
     *
     * @param userId identificador do usuário autenticado, ou {@code "anonimo"}
     * @return snapshot imutável do contexto populado (útil para testes e auditoria)
     */
    public LogContexto inicializar(String userId) {
        var spanContext = capturarSpanContext();

        if (spanContext.isValid()) {
            MDC.put(CAMPO_TRACE_ID, spanContext.getTraceId());
            MDC.put(CAMPO_SPAN_ID, spanContext.getSpanId());
        }

        MDC.put(CAMPO_USER_ID, userId != null ? userId : "anonimo");
        MDC.put(CAMPO_SERVICO, nomeServico);

        return new LogContexto(
                spanContext.isValid() ? spanContext.getTraceId() : null,
                spanContext.isValid() ? spanContext.getSpanId() : null,
                userId != null ? userId : "anonimo",
                nomeServico
        );
    }

    /**
     * Registra o contexto de classe e método interceptado.
     * Chamado pelo {@link br.com.vsjr.labs.log.interceptor.LogInterceptor}.
     */
    public void registrarLocalizacao(String classe, String metodo) {
        MDC.put(CAMPO_CLASSE, classe);
        MDC.put(CAMPO_METODO, metodo);
    }

    /**
     * Remove todos os campos do MDC.
     *
     * <p>Deve sempre ser chamado em bloco {@code finally} para evitar
     * vazamento de contexto entre threads no pool do Vert.x.</p>
     */
    public void limpar() {
        MDC.clear();
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private SpanContext capturarSpanContext() {
        return Span.current().getSpanContext();
    }
}