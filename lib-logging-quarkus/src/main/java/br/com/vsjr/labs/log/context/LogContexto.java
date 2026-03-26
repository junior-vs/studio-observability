package br.com.vsjr.labs.log.context;

/**
 * Snapshot imutável do contexto de correlação de uma requisição.
 *
 * <p>Produzido pelo {@link GerenciadorContextoLog} a partir da identidade autenticada
 * e do nome do serviço. Pode ser inspecionado em testes sem dependência de
 * infraestrutura de MDC.</p>
 *
 * <p>Este record pertence exclusivamente à camada de logging. Dados de rastreamento
 * distribuído ({@code traceId}, {@code spanId}) são responsabilidade do módulo
 * {@link br.com.vsjr.labs.log.tracing.GerenciadorRastreamento}.</p>
 *
 * <p>Usa {@code record} do Java 21: imutável, thread-safe e com
 * {@code equals/hashCode/toString} gerados sem boilerplate.</p>
 *
 * @param userId  identificador do usuário autenticado, ou {@code "anonimo"}
 * @param servico nome do microsserviço
 */
public record LogContexto(
        String userId,
        String servico
) {

    /**
     * Contexto vazio: usado quando nenhuma requisição está ativa (ex: testes, jobs).
     */
    public static final LogContexto VAZIO = new LogContexto("anonimo", "desconhecido");
}
