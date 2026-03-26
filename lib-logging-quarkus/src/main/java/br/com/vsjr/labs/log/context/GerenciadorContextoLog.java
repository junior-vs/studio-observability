package br.com.vsjr.labs.log.context;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

import java.util.Comparator;

/**
 * Gerencia o ciclo de vida do MDC para a camada de logging.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Propagar {@code userId} e nome do serviço para o MDC</li>
 *   <li>Executar o pipeline de enriquecimento de contexto ({@link EnriquecedorContexto})</li>
 *   <li>Garantir limpeza segura após execução</li>
 *   <li>Produzir {@link LogContexto} inspecionável (útil em testes)</li>
 * </ul>
 *
 * <p>Este bean é responsável exclusivamente pelo contexto de logging.
 * Dados de rastreamento distribuído ({@code traceId}, {@code spanId})
 * são gerenciados pelo módulo de tracing ({@code GerenciadorRastreamento}).</p>
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

    @Inject
    Instance<EnriquecedorContexto> enriquecedores;

    private static final String CAMPO_USER_ID = "userId";
    private static final String CAMPO_SERVICO = "servico";

    /**
     * Inicializa o MDC com o contexto de identificação da requisição atual.
     *
     * <p>Popula apenas {@code userId} e {@code servico}. Os campos de rastreamento
     * distribuído ({@code traceId}, {@code spanId}) são responsabilidade do
     * {@code GerenciadorRastreamento}, que deve ser chamado na mesma fase de filtro.</p>
     *
     * @param userId identificador do usuário autenticado, ou {@code "anonimo"}
     * @return snapshot imutável do contexto populado (útil para testes e auditoria)
     */
    public LogContexto inicializar(String userId) {
        var uid = userId != null ? userId : "anonimo";
        MDC.put(CAMPO_USER_ID, uid);
        MDC.put(CAMPO_SERVICO, nomeServico);
        return new LogContexto(uid, nomeServico);
    }

    /**
     * Executa o pipeline de enriquecimento do contexto de logging para a invocação interceptada.
     *
     * <p>Cada {@link EnriquecedorContexto} descoberto via CDI é executado em ordem
     * crescente de {@link EnriquecedorContexto#prioridade()}, adicionando campos ao MDC.
     * Deve ser chamado no início do bloco interceptado; {@link #limparEnriquecimento()}
     * deve ser chamado no {@code finally} correspondente.</p>
     *
     * @param contexto contexto CDI da invocação
     */
    public void enriquecer(InvocationContext contexto) {
        enriquecedores.stream()
                .sorted(Comparator.comparingInt(EnriquecedorContexto::prioridade))
                .forEach(e -> e.enriquecer(contexto));
    }

    /**
     * Remove do MDC todas as chaves gerenciadas pelo pipeline de enriquecimento.
     *
     * <p>Deve ser chamado em bloco {@code finally} após {@link #enriquecer(InvocationContext)}
     * para garantir que campos de localização não contaminem logs subsequentes
     * na mesma thread.</p>
     */
    public void limparEnriquecimento() {
        enriquecedores.stream()
                .flatMap(e -> e.chavesMdc().stream())
                .forEach(MDC::remove);
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

}