package br.com.vsjr.labs.observability;

/**
 * Valores sentinela canônicos utilizados como fallback quando informações
 * obrigatórias estão ausentes ou indisponíveis em tempo de execução.
 *
 * <p>Centraliza aqui toda string de fallback da biblioteca, eliminando literais
 * dispersos e garantindo que todos os módulos (logging, tracing, filtros) produzam
 * os mesmos valores nos campos de saída.</p>
 *
 * <p>As constantes são {@code String} (e não enum) para que possam ser usadas
 * diretamente em contextos que exigem constantes de compilação — como o atributo
 * {@code defaultValue} de {@code @ConfigProperty}.</p>
 *
 * <p>Uso típico:</p>
 * <pre>{@code
 * var uid = userId != null ? userId : ValoresPadrao.USUARIO_ANONIMO;
 *
 * @ConfigProperty(name = "quarkus.application.name",
 *                 defaultValue = ValoresPadrao.APPLICATION_PADRAO)
 * String applicationName;
 * }</pre>
 */
public final class ValoresPadrao {

    private ValoresPadrao() {
    }

    // -------------------------------------------------------------------------
    // Identidade
    // -------------------------------------------------------------------------

    /**
     * Identificador de usuário quando nenhum principal autenticado está disponível.
     * Inserido no campo {@link CamposMdc#USER_ID} do MDC.
     */
    public static final String USUARIO_ANONIMO = "anonimo";

    // -------------------------------------------------------------------------
    // Localização técnica
    // -------------------------------------------------------------------------

    /**
     * Nome de classe ou método quando a localização técnica não pode ser inferida.
     * Usado nos campos {@link CamposMdc#CLASSE}, {@link CamposMdc#METODO},
     * {@link CamposMdc#LOG_CLASSE} e {@link CamposMdc#LOG_METODO}.
     */
    public static final String LOCALIZACAO_DESCONHECIDA = "desconhecido";

    // -------------------------------------------------------------------------
    // Aplicação
    // -------------------------------------------------------------------------

    /**
     * Nome da aplicação quando {@code quarkus.application.name} não está configurado.
     * Valor padrão do {@code @ConfigProperty} em
     * {@link br.com.vsjr.labs.observability.context.GerenciadorContextoLog} e demais
     * beans que injetam o nome da aplicação.
     */
    public static final String APPLICATION_PADRAO = "application-desconhecido";

    // -------------------------------------------------------------------------
    // Evento
    // -------------------------------------------------------------------------

    /**
     * Descrição do evento quando a DSL é construída sem chamar
     * {@link br.com.vsjr.labs.observability.dsl.LOG#registrando(String)} com valor válido.
     * Inserido no campo {@code message} do log estruturado.
     */
    public static final String EVENTO_NAO_INFORMADO = "evento_nao_informado";
}
