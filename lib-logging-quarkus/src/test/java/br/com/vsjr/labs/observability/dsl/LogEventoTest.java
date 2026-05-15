package br.com.vsjr.labs.observability.dsl;

import br.com.vsjr.labs.observability.ValoresPadrao;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link LogEvento}.
 *
 * <p>Valida normalização de campos obrigatórios, fallbacks, imutabilidade do mapa
 * de detalhes e canonização da chave {@code eventType}.</p>
 */
class LogEventoTest {

    // ── Normalização de evento (What) ────────────────────────────────────────

    @Test
    void eventoNulo_deveUsarFallback() {
        var evento = new LogEvento(null, "MinhaClasse", "meuMetodo", null, null, Map.of());
        assertEquals(ValoresPadrao.EVENTO_NAO_INFORMADO, evento.evento());
    }

    @Test
    void eventoVazio_deveUsarFallback() {
        var evento = new LogEvento("   ", "MinhaClasse", "meuMetodo", null, null, Map.of());
        assertEquals(ValoresPadrao.EVENTO_NAO_INFORMADO, evento.evento());
    }

    @Test
    void evento_devePreservarCasoOriginal() {
        var evento = new LogEvento("Pedido Criado com Sucesso", "C", "m", null, null, Map.of());
        assertEquals("Pedido Criado com Sucesso", evento.evento());
    }

    // ── Normalização de classe e método (Where) ──────────────────────────────

    @Test
    void classeNula_deveUsarFallback() {
        var evento = new LogEvento("Pedido criado", null, "criar", null, null, Map.of());
        assertEquals(ValoresPadrao.LOCALIZACAO_DESCONHECIDA, evento.classe());
    }

    @Test
    void classeVazia_deveUsarFallback() {
        var evento = new LogEvento("Pedido criado", "  ", "criar", null, null, Map.of());
        assertEquals(ValoresPadrao.LOCALIZACAO_DESCONHECIDA, evento.classe());
    }

    @Test
    void classe_deveSerNormalizadaEmMinusculo() {
        var evento = new LogEvento("x", "MinhaClasse", "criar", null, null, Map.of());
        assertEquals("minhaclasse", evento.classe());
    }

    @Test
    void metodo_deveSerNormalizadoEmMinusculo() {
        var evento = new LogEvento("x", "MinhaClasse", "MeuMetodo", null, null, Map.of());
        assertEquals("meumetodo", evento.metodo());
    }

    // ── Campos opcionais ─────────────────────────────────────────────────────

    @Test
    void motivoNulo_deveRetornarNulo() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of());
        assertNull(evento.motivo());
    }

    @Test
    void entrypointNulo_deveRetornarNulo() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of());
        assertNull(evento.entrypoint());
    }

    @Test
    void entrypoint_devePreservarCasoOriginal() {
        var evento = new LogEvento("x", "C", "m", null, "API_REST", Map.of());
        assertEquals("API_REST", evento.entrypoint());
    }

    // ── Detalhes ─────────────────────────────────────────────────────────────

    @Test
    void detalhesNulos_deveRetornarMapaVazio() {
        var evento = new LogEvento("x", "C", "m", null, null, null);
        assertTrue(evento.detalhes().isEmpty());
    }

    @Test
    void detalhesVazios_deveRetornarMapaVazio() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of());
        assertTrue(evento.detalhes().isEmpty());
    }

    @Test
    void detalhes_devemSerImutaveis() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of("chave", "valor"));
        assertThrows(UnsupportedOperationException.class, () -> evento.detalhes().put("nova", "entrada"));
    }

    // ── Canonização de chave eventType ───────────────────────────────────────

    @Test
    void detalheEventtype_deveSerCanonicizadoParaEventType() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of("eventtype", "PEDIDO_CRIADO"));
        assertTrue(evento.detalhes().containsKey("eventType"),
                "chave 'eventtype' deve ser canonizada para 'eventType'");
    }

    @Test
    void detalheEventTypeComUnderscore_deveSerCanonicizado() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of("event_type", "PEDIDO_CRIADO"));
        assertTrue(evento.detalhes().containsKey("eventType"),
                "chave 'event_type' deve ser canonizada para 'eventType'");
    }

    @Test
    void detalheComChaveVazia_deveSerIgnorado() {
        var evento = new LogEvento("x", "C", "m", null, null, Map.of("  ", "valor"));
        assertTrue(evento.detalhes().isEmpty(), "chave vazia deve ser descartada");
    }
}
