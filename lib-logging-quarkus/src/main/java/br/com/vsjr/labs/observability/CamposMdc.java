package br.com.vsjr.labs.observability;

/**
 * Catálogo centralizado das chaves MDC canônicas da biblioteca de Logging Sistemático.
 *
 * <p>Cada constante representa uma chave reservada que aparece no JSON estruturado de saída.
 * Toda escrita e leitura no {@code MDC} deve referenciar este enum — nunca literais de
 * string — eliminando divergências de nomenclatura entre módulos e erros de digitação.</p>
 *
 * <p>Organização conforme {@code FIELD_NAMES.md}:</p>
 * <ol>
 *   <li><b>Correlação</b> — inseridos automaticamente por
 *       {@link br.com.vsjr.labs.observability.context.GerenciadorContextoLog} e
 *       {@link br.com.vsjr.labs.observability.tracing.GerenciadorTracing}.</li>
 *   <li><b>Localização técnica</b> — inseridos automaticamente pelo
 *       {@link br.com.vsjr.labs.observability.interceptor.LogInterceptor} via
 *       {@link br.com.vsjr.labs.observability.context.enriquecedor.MetadadosEnriquecedorContexto}.</li>
 *   <li><b>DSL</b> — declarados pelo desenvolvedor via cadeia
 *       {@link br.com.vsjr.labs.observability.dsl.LOG} ({@code .em()}, {@code .porque()},
 *       {@code .como()}).</li>
 * </ol>
 *
 * <p>Campos de negócio declarados via {@code .comDetalhe()} recebem o prefixo
 * {@link #PREFIXO_DETALHE} aplicado dinamicamente — suas chaves não são enumeráveis
 * pois dependem do domínio da aplicação.</p>
 *
 * <p>Uso típico:</p>
 * <pre>{@code
 * MDC.put(CamposMdc.USER_ID.chave(), userId);
 * MDC.put(CamposMdc.TRACE_ID.chave(), spanContext.getTraceId());
 * MDC.remove(CamposMdc.SPAN_ID.chave());
 * }</pre>
 */
public enum CamposMdc {

    // -------------------------------------------------------------------------
    // 1. Identidade e Correlação
    //    Fonte: GerenciadorContextoLog (userId, applicationName)
    //           GerenciadorTracing     (traceId, spanId)
    // -------------------------------------------------------------------------

    /** Identificador do usuário autenticado. {@code "anonimo"} quando não autenticado. */
    USER_ID("userId"),

    /** Nome do microsserviço, lido de {@code quarkus.application.name}. */
    APPLICATION_NAME("applicationName"),

    /** Identificador do trace distribuído W3C. Presente apenas com span OTel ativo. */
    TRACE_ID("traceId"),

    /** Identificador do span atual dentro do trace. Presente apenas com span OTel ativo. */
    SPAN_ID("spanId"),

    // -------------------------------------------------------------------------
    // 2. Localização Técnica
    //    Fonte: MetadadosEnriquecedorContexto via @Logged
    // -------------------------------------------------------------------------

    /** Nome simples da classe interceptada pelo {@code @Logged}. */
    CLASSE("classe"),

    /** Nome do método interceptado pelo {@code @Logged}. */
    METODO("metodo"),

    // -------------------------------------------------------------------------
    // 3. Campos da DSL
    //    Fonte: LOG.em(), LOG.porque(), LOG.como()
    // -------------------------------------------------------------------------

    /** Nome da classe onde o evento ocorreu — declarado via {@code .em(Classe.class, ...)}. */
    LOG_CLASSE("log_classe"),

    /** Nome do método onde o evento ocorreu — declarado via {@code .em(..., "metodo")}. */
    LOG_METODO("log_metodo"),

    /** Causa ou motivação de negócio — declarado via {@code .porque("motivo")}. */
    LOG_MOTIVO("log_motivo"),

    /** Canal ou mecanismo pelo qual o evento chegou — declarado via {@code .como("canal")}. */
    LOG_CANAL("log_canal"),

    // -------------------------------------------------------------------------
    // 4. Tags de Métricas
    //    Fonte: LogInterceptor (metodo.falha counter)
    // -------------------------------------------------------------------------

    /** Nome simples da classe da exceção capturada — tag do counter {@code metodo.falha}. */
    EXCECAO("excecao");

    // -------------------------------------------------------------------------
    // Prefixo de campos de negócio (dinâmicos — não enumeráveis)
    // -------------------------------------------------------------------------

    /**
     * Prefixo aplicado automaticamente pela DSL a cada chave declarada via
     * {@code .comDetalhe(chave, valor)}.
     *
     * <p>Evita colisão com campos reservados de infraestrutura nos índices do
     * Elasticsearch/Loki e distingue visualmente campos de negócio de campos de
     * contexto técnico nas ferramentas de analytics.</p>
     *
     * <p>Exemplo: {@code .comDetalhe("pedidoId", 4821)} → {@code "detalhe_pedidoId"}.</p>
     */
    public static final String PREFIXO_DETALHE = "detalhe_";

    // -------------------------------------------------------------------------

    private final String chave;

    CamposMdc(String chave) {
        this.chave = chave;
    }

    /**
     * Retorna a chave canônica que identifica o campo no JSON estruturado e no MDC.
     *
     * @return chave MDC canônica conforme {@code FIELD_NAMES.md}
     */
    public String chave() {
        return chave;
    }
}
