package br.com.vsjr.labs.observability.dsl;


import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

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
 * LogSistematico
 *     .registrando("Pedido criado")
 *     .em(PedidoService.class, "criar")
 *     .info();
 * }</pre>
 *
 * <p><b>Uso completo com todas as dimensões do 5W1H:</b></p>
 * <pre>{@code
 * LogSistematico
 *     .registrando("Pagamento recusado")
 *     .em(PagamentoService.class, "processar")
 *     .porque("Saldo insuficiente no gateway")
 *     .como("API REST — POST /pagamentos")
 *     .comDetalhe("pedidoId",   pedido.getId())
 *     .comDetalhe("valor",      pedido.getValor())
 *     .comDetalhe("token",      request.token())  // ← mascarado: "****"
 *     .erro(excecao);
 * }</pre>
 *
 * <p><b>Nota sobre o logger:</b> o logger é criado por {@code Logger.getLogger(classe)},
 * que é o padrão idiomático do Quarkus (JBoss Logging). Isso garante compatibilidade
 * com {@code quarkus-logging-json} e com native image sem adaptadores externos.</p>
 */
public final class LogSistematico implements LogEtapas.EtapaOnde, LogEtapas.EtapaOpcional {

    private String evento;
    private Class<?> classeAlvo;
    private String metodo;
    private String motivo;
    private String canal;
    // LinkedHashMap: preserva a ordem de inserção dos detalhes no JSON de saída
    private final LinkedHashMap<String, Object> detalhes = new LinkedHashMap<>();

    private LogSistematico() {
    }

    // ── Ponto de entrada — What ───────────────────────────────────────────────

    /**
     * Inicia a construção do observability com a descrição do evento (dimensão <em>What</em>).
     *
     * @param evento o que está acontecendo — ex: "Pedido criado", "Login falhou"
     * @return etapa seguinte, que exige a declaração do Where
     */
    public static LogEtapas.EtapaOnde registrando(String evento) {
        var builder = new LogSistematico();
        builder.evento = evento;
        return builder;
    }

    // ── Etapa obrigatória — Where ─────────────────────────────────────────────

    @Override
    public LogEtapas.EtapaOpcional em(Class<?> classe, String metodo) {
        this.classeAlvo = classe;
        this.metodo = normalizarTextoOpcional(metodo, true);
        return this;
    }

    // ── Etapas opcionais ──────────────────────────────────────────────────────

    @Override
    public LogEtapas.EtapaOpcional porque(String motivo) {
        this.motivo = normalizarTextoOpcional(motivo, true);
        return this;
    }

    @Override
    public LogEtapas.EtapaOpcional como(String canal) {
        this.canal = normalizarTextoOpcional(canal, true);
        return this;
    }

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

    // ── Terminadores ──────────────────────────────────────────────────────────


    @Override
    public void info() {
        emitir(Logger.Level.INFO, null);
    }


    @Override
    public void debug() {
        emitir(Logger.Level.DEBUG, null);
    }

    @Override
    public void warn() {
        emitir(Logger.Level.WARN, null);
    }

    @Override
    public void erro(Throwable causa) {
        emitir(Logger.Level.ERROR, causa);
    }

    @Override
    public <T extends Throwable> void erroERelanca(T causa) throws T {
        emitir(Logger.Level.ERROR, causa);
        throw causa;
    }

    // ── Emissão ───────────────────────────────────────────────────────────────

    /**
     * Monta o {@link br.com.vsjr.labs.observability.dsl.LogEvento} e o emite via JBoss Logging.
     *
     * <p>As dimensões estruturais (classe, método, motivo, canal) são inseridas
     * no MDC imediatamente antes da emissão e removidas logo após — garantindo
     * que campos do evento atual não contaminem logs subsequentes na mesma thread.</p>
     *
     * <p>Os detalhes de negócio são prefixados com {@code "detalhe_"} para
     * diferenciá-los dos campos de infraestrutura no JSON de saída.</p>
     */
    private void emitir(Logger.Level level, Throwable causa) {
        var eventoLog = criarEvento();
        var classeLogger = classeAlvo != null ? classeAlvo : LogSistematico.class;
        var logger = Logger.getLogger(classeLogger);
        if (!logger.isEnabled(level)) return;

        // Popula MDC com dimensões estruturais do evento
        MDC.put("log_classe", eventoLog.classe());
        MDC.put("log_metodo", eventoLog.metodo());
        if (eventoLog.motivo() != null) MDC.put("log_motivo", eventoLog.motivo());
        if (eventoLog.canal() != null) MDC.put("log_canal", eventoLog.canal());

        // Detalhes de negócio: cada entrada vira um campo JSON de primeiro nível
        eventoLog.detalhes().forEach((chave, valor) -> MDC.put("detalhe_" + chave, valor != null ? valor.toString() : "null"));


        try {
            // Emissão via JBoss Logging — integração nativa com quarkus-logging-json
            if (causa != null) {
                logger.log(level, eventoLog.evento(), causa);
            } else {
                logger.log(level, eventoLog.evento());
            }
        } finally {
            // Limpeza dos campos do evento: não remove o contexto da requisição
            // (traceId, userId), que é responsabilidade do GerenciadorContextoLog
            MDC.remove("log_classe");
            MDC.remove("log_metodo");
            MDC.remove("log_motivo");
            MDC.remove("log_canal");
            eventoLog.detalhes().keySet().forEach(chave -> MDC.remove("detalhe_" + chave));



        }
    }

    private LogEvento criarEvento() {
        var nomeClasse = classeAlvo != null
                ? classeAlvo.getSimpleName()
                : "desconhecido";
        return new LogEvento(
                normalizarTextoObrigatorio(evento, "evento_nao_informado", false),
                nomeClasse,
                metodo,
                motivo,
                canal,
                detalhes
        );
    }

    private static String normalizarTextoObrigatorio(String valor, String fallback, boolean lowerCase) {
        var normalizado = normalizarTextoOpcional(valor, lowerCase);
        return normalizado != null ? normalizado : fallback;
    }

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
