package br.com.vsjr.labs.observability.dsl;

import br.com.vsjr.labs.observability.ValoresPadrao;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogEventoTest {

    @Test
    void deveNormalizarCamposTextuaisETratarDetalhesNulos() {
        var detalhes = new LinkedHashMap<String, Object>();
        detalhes.put(" ChaveX ", 10);
        detalhes.put(" TOKEN ", null);
        detalhes.put("eventType", "ORDER_COMPLETED");
        detalhes.put(" ", "ignorado");
        detalhes.put(null, "ignorado");

        var evento = new LogEvento(
                " Evento Criado ",
                " PedidoService ",
                " Processar ",
                null,
                " API REST ",
                detalhes
        );

        assertEquals("Evento Criado", evento.evento());
        assertEquals("pedidoservice", evento.classe());
        assertEquals("processar", evento.metodo());
        assertNull(evento.motivo());
        assertEquals("api rest", evento.canal());
        assertEquals(3, evento.detalhes().size());
        assertTrue(evento.detalhes().containsKey("chavex"));
        assertTrue(evento.detalhes().containsKey("token"));
        assertTrue(evento.detalhes().containsKey("eventType"));
        assertEquals("ORDER_COMPLETED", evento.detalhes().get("eventType"));
        assertEquals("null", evento.detalhes().get("token"));
    }

    @Test
    void deveAplicarFallbackQuandoCamposObrigatoriosForemNulos() {
        var evento = new LogEvento(null, null, null, "", "", Map.of());

        assertEquals(ValoresPadrao.EVENTO_NAO_INFORMADO, evento.evento());
        assertEquals(ValoresPadrao.LOCALIZACAO_DESCONHECIDA, evento.classe());
        assertEquals(ValoresPadrao.LOCALIZACAO_DESCONHECIDA, evento.metodo());
        assertNull(evento.motivo());
        assertNull(evento.canal());
    }
}
