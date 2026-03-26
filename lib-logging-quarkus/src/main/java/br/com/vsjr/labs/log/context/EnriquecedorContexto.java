package br.com.vsjr.labs.log.context;

import jakarta.interceptor.InvocationContext;

import java.util.Set;

/**
 * Contrato do pipeline de enriquecimento do contexto de logging (Chain of Responsibility).
 *
 * <p>Cada implementação contribui com campos MDC para o contexto da invocação
 * interceptada. O {@link GerenciadorContextoLog} executa todos os enriquecedores
 * descobertos via CDI em ordem crescente de {@link #prioridade()}.</p>
 *
 * <p><b>Limpeza automática:</b> após a execução do método interceptado,
 * {@link GerenciadorContextoLog#limparEnriquecimento()} remove do MDC todas as
 * chaves declaradas por {@link #chavesMdc()}. Inclua todas as chaves potenciais,
 * mesmo as inseridas condicionalmente — {@code MDC.remove} em chave inexistente é seguro.</p>
 *
 * <p><b>Implementação mínima:</b></p>
 * <pre>{@code
 * @ApplicationScoped
 * public class EnriquecedorOperacao implements EnriquecedorContexto {
 *
 *     @Override
 *     public void enriquecer(InvocationContext contexto) {
 *         MDC.put("operacao.tipo", extrairTipo(contexto));
 *     }
 *
 *     @Override
 *     public Set<String> chavesMdc() {
 *         return Set.of("operacao.tipo");
 *     }
 *
 *     @Override
 *     public int prioridade() { return 100; }
 * }
 * }</pre>
 */
public interface EnriquecedorContexto {

    /**
     * Enriquece o MDC com campos adicionais para a invocação interceptada.
     *
     * @param contexto contexto CDI da invocação
     */
    void enriquecer(InvocationContext contexto);

    /**
     * Declara as chaves MDC que este enriquecedor pode inserir.
     *
     * <p>Usado pelo {@link GerenciadorContextoLog} para limpeza automática
     * após a execução do método interceptado.</p>
     */
    Set<String> chavesMdc();

    /**
     * Ordem de execução na cadeia — valor menor executa primeiro.
     * Padrão: {@link Integer#MAX_VALUE}.
     */
    default int prioridade() {
        return Integer.MAX_VALUE;
    }
}
