package br.com.vsjr.labs.observability.dsl;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.ValoresPadrao;
import br.com.vsjr.labs.observability.security.LocalizacaoMetodo;
import br.com.vsjr.labs.observability.security.SanitizadorDados;

import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Ponto de entrada público da DSL de logging sistemático para Quarkus.
 *
 * <p>A DSL é construída sobre {@code sealed interfaces} do Java 21:
 * o compilador valida a sequência de chamadas, tornando logs incompletos
 * erros de compilação em vez de bugs silenciosos em produção.</p>
 *
 * <p><b>Uso mínimo obrigatório (What + Where):</b></p>
 * <pre>{@code
 * LOG
 *     .registrando("Pedido criado")
 *     .em(PedidoService.class, "criar")
 *     .info();
 * }</pre>
 *
 * <p><b>Uso completo com todas as dimensões do 5W1H:</b></p>
 * <pre>{@code
 * LOG
 *     .registrando("Pagamento recusado")
 *     .em(PagamentoService.class, "processar")
 *     .porque("Saldo insuficiente no gateway")
 *     .como("API REST - POST /pagamentos")
 *     .comDetalhe("pedidoId",   pedido.getId())
 *     .comDetalhe("valor",      pedido.getValor())
 *     .comDetalhe("token",      request.token())  // <- mascarado: "****"
 *     .erro(excecao);
 * }</pre>
 *
 * <p><b>Nota sobre o logger:</b> o logger é criado por {@code Logger.getLogger(classe)},
 * que é o padrão idiomático do Quarkus (JBoss Logging). Isso garante compatibilidade
 * com {@code quarkus-logging-json} e com native image sem adaptadores externos.</p>
 *
 * <p>Esta classe implementa as etapas da DSL através de {@link LogEtapas.EtapaOnde}
 * e {@link LogEtapas.EtapaOpcional}, garantindo uma construção guiada do evento
 * até sua emissão.</p>
 */
public final class LOG implements LogEtapas.EtapaOnde, LogEtapas.EtapaOpcional {

    /**
     * Descrição principal do evento de negócio que será registrada no log.
     */
    private String evento;

    /**
     * Classe associada ao evento — usada para obter o logger JBoss correspondente.
     * {@code null} quando o desenvolvedor não chamou {@link #em}; nesse caso o
     * logger é obtido a partir de {@link LOG} e o nome da classe no evento é
     * {@code "desconhecido"}.
     */
    private Class<?> classeAlvo;

    /**
     * Localização técnica estruturada do evento (classe simples + método).
     * Construída em {@link #em} via {@link LocalizacaoMetodo#of} para manter
     * o mesmo contrato de formato usado pelos interceptores.
     */
    private LocalizacaoMetodo.Localizacao localizacao;

    /**
     * Motivo contextual opcional do evento.
     */
    private String motivo;

    /**
     * Canal, meio ou forma de execução associada ao evento.
     */
    private String canal;

    /**
     * Detalhes complementares do evento.
     *
     * <p>{@link LinkedHashMap} é usado para preservar a ordem de inserção,
     * o que ajuda a manter previsibilidade na serialização/visualização do JSON de saída.</p>
     */
    private final LinkedHashMap<String, Object> detalhes = new LinkedHashMap<>();

    /**
     * Construtor privado para forçar o uso do método estático de entrada da DSL
     * {@link #registrando(String)}.
     */
    private LOG() {
    }

    // -- Ponto de entrada - What -----------------------------------------------

    /**
     * Inicia a construção do observability com a descrição do evento (dimensão <em>What</em>).
     *
     * @param evento o que está acontecendo - ex: "Pedido criado", "Login falhou"
     * @return etapa seguinte, que exige a declaração do Where
     */
    public static LogEtapas.EtapaOnde registrando(String evento) {
        var builder = new LOG();
        builder.evento = evento;
        return builder;
    }

    // -- Etapa obrigatória - Where ---------------------------------------------

    /**
     * Define o contexto técnico mínimo do evento, informando a classe e o método
     * associados à ocorrência registrada.
     *
     * <p>O nome do método é normalizado para minúsculas e tem espaços removidos
     * nas extremidades. Se vier vazio, será tratado como ausente.</p>
     *
     * @param classe classe de origem ou de responsabilidade pelo evento
     * @param metodo nome do método relacionado ao evento
     * @return a etapa opcional da DSL para enriquecimento e emissão do log
     */
    @Override
    public LogEtapas.EtapaOpcional em(Class<?> classe, String metodo) {
        this.classeAlvo = classe;
        if (classe != null) {
            var metodoNorm = normalizarTextoOpcional(metodo, true);
            this.localizacao = LocalizacaoMetodo.of(classe, metodoNorm != null ? metodoNorm : ValoresPadrao.LOCALIZACAO_DESCONHECIDA);
        }
        return this;
    }

    // -- Etapas opcionais -------------------------------------------------------

    /**
     * Informa o motivo do evento registrado.
     *
     * @param motivo justificativa, causa ou razão associada ao evento
     * @return a própria etapa opcional para encadeamento fluente
     */
    @Override
    public LogEtapas.EtapaOpcional porque(String motivo) {
        this.motivo = normalizarTextoOpcional(motivo, true);
        return this;
    }

    /**
     * Informa o canal, meio ou forma pela qual o evento aconteceu.
     *
     * @param canal descrição do canal, como "api rest", "mensageria", "batch"
     * @return a própria etapa opcional para encadeamento fluente
     */
    @Override
    public LogEtapas.EtapaOpcional como(String canal) {
        this.canal = normalizarTextoOpcional(canal, true);
        return this;
    }

    /**
     * Adiciona um detalhe complementar ao evento.
     *
     * <p>A chave é normalizada e o valor é automaticamente sanitizado por
     * {@link SanitizadorDados}, evitando exposição acidental de informações sensíveis.</p>
     *
     * @param chave nome do detalhe
     * @param valor valor do detalhe
     * @return a própria etapa opcional para encadeamento fluente
     */
    @Override
    public LogEtapas.EtapaOpcional comDetalhe(String chave, Object valor) {
        var chaveNormalizada = normalizarTextoOpcional(chave, true);
        if (chaveNormalizada == null) {
            return this;
        }
        // Sanitização automática: credenciais e dados pessoais são mascarados aqui.
        detalhes.put(chaveNormalizada, SanitizadorDados.sanitizar(chaveNormalizada, valor));
        return this;
    }

    // -- Terminadores -----------------------------------------------------------

    /**
     * Emite o evento no nível INFO.
     */
    @Override
    public void info() {
        emitir(Logger.Level.INFO, null);
    }

    /**
     * Emite o evento no nível DEBUG.
     */
    @Override
    public void debug() {
        emitir(Logger.Level.DEBUG, null);
    }

    /**
     * Emite o evento no nível WARN.
     */
    @Override
    public void warn() {
        emitir(Logger.Level.WARN, null);
    }

    /**
     * Emite o evento no nível ERROR associando a exceção informada.
     *
     * @param causa exceção que motivou o erro a ser registrado
     */
    @Override
    public void erro(Throwable causa) {
        emitir(Logger.Level.ERROR, causa);
    }

    /**
     * Emite o evento no nível ERROR e relança a mesma exceção.
     *
     * <p>Útil para registrar falhas sem perder o fluxo normal de propagação do erro.</p>
     *
     * @param causa exceção a ser registrada e relançada
     * @param <T> tipo concreto da exceção
     * @throws T a própria exceção recebida
     */
    @Override
    public <T extends Throwable> void erroERelanca(T causa) throws T {
        emitir(Logger.Level.ERROR, causa);
        throw causa;
    }

    // -- Emissão ----------------------------------------------------------------

    /**
     * Monta o {@link br.com.vsjr.labs.observability.dsl.LogEvento} e o emite via JBoss Logging.
     *
     * <p>As dimensões estruturais (classe, método, motivo, canal) são inseridas
     * no MDC imediatamente antes da emissão e removidas logo após - garantindo
     * que campos do evento atual não contaminem logs subsequentes na mesma thread.</p>
     *
     * <p>Os detalhes de negócio são prefixados com {@code "detalhe_"} para
     * diferenciá-los dos campos de infraestrutura no JSON de saída.</p>
     *
     * @param level nível de log que será utilizado na emissão
     * @param causa exceção opcional a ser anexada ao log; pode ser {@code null}
     */
    private void emitir(Logger.Level level, Throwable causa) {
        var eventoLog = criarEvento();
        var classeLogger = classeAlvo != null ? classeAlvo : LOG.class;
        var logger = Logger.getLogger(classeLogger);
        if (!logger.isEnabled(level)) {
            return;
        }

        // Popula MDC com dimensões estruturais do evento
        MDC.put(CamposMdc.LOG_CLASSE.chave(), eventoLog.classe());
        MDC.put(CamposMdc.LOG_METODO.chave(), eventoLog.metodo());
        if (eventoLog.motivo() != null) {
            MDC.put(CamposMdc.LOG_MOTIVO.chave(), eventoLog.motivo());
        }
        if (eventoLog.canal() != null) {
            MDC.put(CamposMdc.LOG_CANAL.chave(), eventoLog.canal());
        }

        // Detalhes de negócio: cada entrada vira um campo JSON de primeiro nível
        eventoLog.detalhes().forEach((chave, valor) -> MDC.put(CamposMdc.PREFIXO_DETALHE + chave, valor != null ? valor.toString() : "null"));

        try {
            // Emissão via JBoss Logging - integração nativa com quarkus-logging-json
            if (causa != null) {
                logger.log(level, eventoLog.evento(), causa);
            } else {
                logger.log(level, eventoLog.evento());
            }
        } finally {
            // Limpeza dos campos do evento: não remove o contexto da requisição
            // (traceId, userId), que é responsabilidade do GerenciadorContextoLog
            MDC.remove(CamposMdc.LOG_CLASSE.chave());
            MDC.remove(CamposMdc.LOG_METODO.chave());
            MDC.remove(CamposMdc.LOG_MOTIVO.chave());
            MDC.remove(CamposMdc.LOG_CANAL.chave());
            eventoLog.detalhes().keySet().forEach(chave -> MDC.remove(CamposMdc.PREFIXO_DETALHE + chave));
        }
    }

    /**
     * Cria uma representação imutável do evento atual a partir do estado acumulado
     * pela DSL.
     *
     * <p>Se a classe não for informada, usa {@code "desconhecido"} como nome técnico.
     * Se o evento vier nulo ou vazio, usa {@code "evento_nao_informado"} como fallback.</p>
     *
     * @return instância de {@link LogEvento} pronta para ser emitida
     */
    private LogEvento criarEvento() {
        return new LogEvento(
                normalizarTextoObrigatorio(evento, ValoresPadrao.EVENTO_NAO_INFORMADO, false),
                localizacao != null ? localizacao.classeSimples() : ValoresPadrao.LOCALIZACAO_DESCONHECIDA,
                localizacao != null ? localizacao.metodo() : null,
                motivo,
                canal,
                detalhes
        );
    }

    /**
     * Normaliza um texto obrigatório, aplicando a mesma regra de normalização
     * de campos opcionais, mas retornando um valor fallback quando o conteúdo
     * for nulo, vazio ou em branco.
     *
     * @param valor texto original
     * @param fallback valor padrão a ser utilizado quando o texto não for válido
     * @param lowerCase indica se o conteúdo deve ser convertido para minúsculas
     * @return texto normalizado ou o fallback informado
     */
    private static String normalizarTextoObrigatorio(String valor, String fallback, boolean lowerCase) {
        var normalizado = normalizarTextoOpcional(valor, lowerCase);
        return normalizado != null ? normalizado : fallback;
    }

    /**
     * Normaliza um texto opcional removendo espaços excedentes e, se desejado,
     * convertendo o conteúdo para minúsculas usando {@link Locale#ROOT}.
     *
     * @param valor texto original
     * @param lowerCase indica se o texto deve ser convertido para minúsculas
     * @return texto normalizado ou {@code null} quando o valor for nulo ou vazio
     */
    private static String normalizarTextoOpcional(String valor, boolean lowerCase) {
        if (valor == null) {
            return null;
        }
        var normalizado = valor.trim();
        if (normalizado.isEmpty()) {
            return null;
        }
        return lowerCase ? normalizado.toLowerCase(Locale.ROOT) : normalizado;
    }
}