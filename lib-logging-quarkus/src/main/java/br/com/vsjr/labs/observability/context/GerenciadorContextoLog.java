package br.com.vsjr.labs.observability.context;


import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.ValoresPadrao;
import br.com.vsjr.labs.observability.context.enriquecedor.EnriquecedorContexto;
import br.com.vsjr.labs.observability.tracing.GerenciadorTracing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
 * são gerenciados pelo módulo de tracing ({@code GerenciadorTracing}).</p>
 *
 * <p>Usa {@code org.jboss.logging.MDC} — implementação nativa do Quarkus.
 * O SLF4J delega para JBoss Logging internamente de qualquer forma; usar
 * a API nativa elimina uma camada de indireção desnecessária.</p>
 */
@ApplicationScoped
public class GerenciadorContextoLog {

    String applicationName;
    Instance<EnriquecedorContexto> enriquecedores;

    /**
     * Construtor CDI: recebe o nome da aplicação e o conjunto de enriquecedores disponíveis.
     *
     * @param applicationName nome do microsserviço, lido de {@code quarkus.application.name};
     *                        padrão {@code "application-desconhecido"}
     * @param enriquecedores  todos os beans {@link EnriquecedorContexto} descobertos via CDI
     */
    public GerenciadorContextoLog(
            @ConfigProperty(name = "quarkus.application.name", defaultValue = ValoresPadrao.APPLICATION_PADRAO) String applicationName,
            Instance<EnriquecedorContexto> enriquecedores) {
        this.applicationName = applicationName;
        this.enriquecedores = enriquecedores;
    }



    /**
     * Inicializa o MDC com o contexto de identificação da requisição atual.
     *
    * <p>Popula as chaves MDC {@code userId} e {@code applicationName}. Os campos de rastreamento
     * distribuído ({@code traceId}, {@code spanId}) são responsabilidade do
     * {@code GerenciadorTracing}, que deve ser chamado na mesma fase de filtro.</p>
     *
     * @param userId identificador do usuário autenticado; {@code null} é coercido para
     *               {@code "anonimo"}
     * @return snapshot imutável do contexto registrado no MDC
     */
    public LogContexto inicializar(String userId) {
        var uid = userId != null ? userId : ValoresPadrao.USUARIO_ANONIMO;
        MDC.put(CamposMdc.USER_ID.chave(), uid);
        MDC.put(CamposMdc.APPLICATION_NAME.chave(), applicationName);
        return new LogContexto(uid, applicationName);
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

    /**
     * Snapshot imutável do contexto de correlação de uma requisição.
     *
     * <p>Produzido pelo {@link GerenciadorContextoLog} a partir da identidade autenticada
     * e do nome da aplicação. Pode ser inspecionado em testes sem dependência de
     * infraestrutura de MDC.</p>
     *
     * <p>Este record pertence exclusivamente à camada de logging. Dados de rastreamento
     * distribuído ({@code traceId}, {@code spanId}) são responsabilidade do módulo
     * {@link GerenciadorTracing}.</p>
     *
     * <p>Usa {@code record} do Java 21: imutável, thread-safe e com
     * {@code equals/hashCode/toString} gerados sem boilerplate.</p>
     *
     * @param userId  identificador do usuário autenticado, ou {@code "anonimo"}
     * @param applicationName nome do microsserviço
     */
    public record LogContexto(
            String userId,
            String applicationName
    ) {

        /**
         * Contexto vazio: usado quando nenhuma requisição está ativa (ex: testes, jobs).
         */
        public static final LogContexto VAZIO = new LogContexto(ValoresPadrao.USUARIO_ANONIMO, ValoresPadrao.LOCALIZACAO_DESCONHECIDA);
    }

}