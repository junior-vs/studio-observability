package br.com.vsjr.labs.observability.context.enriquecedor;

/**
 * Contrato comum de prioridade para pipelines de enriquecimento.
 *
 * <p>Implementado por {@link br.com.vsjr.labs.observability.context.enriquecedor.logs.EnriquecedorContexto EnriquecedorContexto} (MDC) e
 * {@link br.com.vsjr.labs.observability.context.enriquecedor.tracing.EnriquecedorTracing EnriquecedorTracing} (OTel Spans). Os gerenciadores
 * executam os enriquecedores em ordem crescente deste valor:</p>
 *
 * <ul>
 *   <li>{@code 10–50} — enriquecedores obrigatórios de infraestrutura</li>
 *   <li>{@code 100+}  — enriquecedores opcionais de negócio</li>
 * </ul>
 *
 * <p>Valor padrão: {@link Integer#MAX_VALUE} (executado por último).</p>
 */
public interface Priorizavel {

    /**
     * Ordem de execução na cadeia — valor menor executa primeiro.
     *
     * @return prioridade de execução (padrão: {@link Integer#MAX_VALUE})
     */
    default int prioridade() {
        return Integer.MAX_VALUE;
    }
}


