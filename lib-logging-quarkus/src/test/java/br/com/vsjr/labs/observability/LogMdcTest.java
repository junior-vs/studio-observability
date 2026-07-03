package br.com.vsjr.labs.observability;

import br.com.vsjr.labs.observability.dsl.Log;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testa o ciclo de vida do MDC durante a emissão da DSL de logging.
 *
 * <p>A estratégia de captura usa um {@link java.util.logging.Handler} JUL registrado
 * na raiz do logger. Como o JBoss LogManager é o gerenciador JUL nas execuções
 * de teste (configurado via surefire), o {@code Handler.publish()} executa
 * sincronamente na mesma thread que emite o log — garantindo que o MDC
 * capturado reflita exatamente o estado no momento da emissão.</p>
 *
 * <p>Garante os critérios de aceite da Sprint 2: MDC populado durante a emissão
 * e limpo após; preservação de case em motivo e detalhes; mascaramento de dados
 * sensíveis.</p>
 */
class LogMdcTest {

    /** MDC capturado sincronamente dentro do handler durante a emissão. */
    private Map<String, Object> mdcCapturado;
    private Handler handlerCaptura;

    @BeforeEach
    void configurarHandler() {
        mdcCapturado = new HashMap<>();
        handlerCaptura = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // Captura snapshot do MDC no momento exato da emissão via SLF4J (vê todos os campos)
                var mapa = org.slf4j.MDC.getCopyOfContextMap();
                if (mapa != null) {
                    mdcCapturado.putAll(mapa);
                }
                // Captura valores Object (ex: Integer) diretamente do JBoss MDC para detalhes
                var prefixo = CamposMdc.PREFIXO_DETALHE;
                for (var key : new java.util.HashSet<>(mdcCapturado.keySet())) {
                    if (key.startsWith(prefixo)) {
                        var obj = MDC.get(key);
                        if (obj != null) mdcCapturado.put(key, obj);
                    }
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        java.util.logging.Logger.getLogger("").addHandler(handlerCaptura);
    }

    @AfterEach
    void removerHandler() {
        java.util.logging.Logger.getLogger("").removeHandler(handlerCaptura);
        MDC.clear();
    }

    // ── MDC populado durante emissão ─────────────────────────────────────────

    @Test
    void emissao_devePobularMdcComClasseEMetodo() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "meuMetodo")
                .info();

        assertEquals("logmdctest", mdcCapturado.get("log_classe"),
                "log_classe deve ser nome simples da classe em minúsculas");
        assertEquals("meumetodo", mdcCapturado.get("log_metodo"),
                "log_metodo deve ser nome do método em minúsculas");
    }

    @Test
    void emissao_devePobularMdcComEntrypoint() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "processar")
                .como(EntrypointTeste.API_REST)
                .info();

        assertEquals("API_REST", mdcCapturado.get("log_entrypoint"),
                "log_entrypoint deve preservar valor do enum");
    }

    @Test
    void emissao_devePobularMdcComMotivo_preservandoCase() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "m")
                .porque("Saldo Insuficiente")
                .info();

        assertEquals("Saldo Insuficiente", mdcCapturado.get("log_motivo"),
                "motivo deve preservar casing original");
    }

    @Test
    void emissao_devePobularMdcComDetalhe_preservandoCaseNaChave() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "m")
                .comDetalhe("pedidoId", 4821)
                .info();

        assertTrue(mdcCapturado.containsKey("detalhe_pedidoId"),
                "chave do detalhe deve preservar casing original com prefixo detalhe_");
        assertEquals("4821", String.valueOf(mdcCapturado.get("detalhe_pedidoId")),
                "valor do detalhe deve ser preservado como string");
    }

    // ── Mascaramento de dados sensíveis ──────────────────────────────────────

    @Test
    void emissao_deveMascararTokenNasDetalhe() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "m")
                .comDetalhe("token", "meu-bearer-token-secreto")
                .info();

        assertEquals("****", mdcCapturado.get("detalhe_token"),
                "valores com chave 'token' devem ser mascarados como '****'");
    }

    @Test
    void emissao_deveMascararCpfNasDetalhe() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "m")
                .comDetalhe("cpf", "123.456.789-00")
                .info();

        assertEquals("[PROTEGIDO]", mdcCapturado.get("detalhe_cpf"),
                "valores com chave 'cpf' devem ser mascarados como '[PROTEGIDO]'");
    }

    // ── MDC limpo após emissão ────────────────────────────────────────────────

    @Test
    void aposEmissao_camposDslDevemSerRemovidosDoMdc() {
        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "m")
                .porque("Motivo Qualquer")
                .comDetalhe("campo", "valor")
                .info();

        assertNull(MDC.get(CamposMdc.LOG_CLASSE.chave()),     "log_classe deve ser removido após emissão");
        assertNull(MDC.get(CamposMdc.LOG_METODO.chave()),     "log_metodo deve ser removido após emissão");
        assertNull(MDC.get(CamposMdc.LOG_MOTIVO.chave()),     "log_motivo deve ser removido após emissão");
        assertNull(MDC.get(CamposMdc.LOG_ENTRYPOINT.chave()), "log_entrypoint deve ser removido após emissão");
        assertFalse(MDC.get(CamposMdc.LOG_CLASSE.chave()) != null ||
                        MDC.get(CamposMdc.LOG_MOTIVO.chave()) != null ||
                        MDC.get(CamposMdc.LOG_ENTRYPOINT.chave()) != null ||
                        MDC.get("detalhe_campo") != null,
                "campos detalhe_* devem ser removidos após emissão");
    }

    @Test
    void aposEmissao_camposDeContextoRequisicaoDevemPermanecerNoMdc() {
        MDC.put(CamposMdc.USER_ID.chave(), "usuario-123");
        MDC.put(CamposMdc.APPLICATION_NAME.chave(), "minha-app");

        Log.registrando(EventoTeste.OPERACAO_EXECUTADA)
                .em(LogMdcTest.class, "m")
                .info();

        assertEquals("usuario-123", MDC.get(CamposMdc.USER_ID.chave()),
                "userId do contexto de requisição não deve ser removido pela emissão da DSL");
        assertEquals("minha-app", MDC.get(CamposMdc.APPLICATION_NAME.chave()),
                "applicationName do contexto de requisição não deve ser removido pela emissão da DSL");
    }

    // ── Tipos auxiliares locais para os testes ───────────────────────────────

    enum EventoTeste implements br.com.vsjr.labs.observability.dsl.Event {
        OPERACAO_EXECUTADA;

        @Override
        public String getEvent() {
            return name();
        }
    }

    enum EntrypointTeste implements br.com.vsjr.labs.observability.dsl.Entrypoint {
        API_REST;

        @Override
        public String getEntrypoint() {
            return name();
        }
    }
}

