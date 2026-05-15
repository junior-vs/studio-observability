package br.com.vsjr.labs.observability;

import br.com.vsjr.labs.observability.dsl.Log;
import br.com.vsjr.labs.observability.dsl.LogEtapas;
import br.com.vsjr.labs.observability.dsl.LogEvento;
import br.com.vsjr.labs.observability.dsl.enums.EntrypointEnum;
import br.com.vsjr.labs.observability.dsl.enums.EventEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogDslTest {

    @Test
    void aqui_deve_capturar_classe_e_metodo_do_chamador() throws Exception {
        var etapa = Log.registrando(EventEnum.EVENTO_GENERICO).aqui();

        var evento = extrairEvento(etapa);

        assertEquals("logdsltest", evento.classe());
        assertEquals("aqui_deve_capturar_classe_e_metodo_do_chamador", evento.metodo());
    }
    
    @Test
    void registrando_deve_rejeitar_evento_nulo() {
        assertThrows(NullPointerException.class, () -> Log.registrando(null));
    }

    @Test
    void como_deve_aceitar_entrypoint_tipado() throws Exception {
        var etapa = Log.registrando(EventEnum.EVENTO_GENERICO)
                .em(LogDslTest.class, "emitir")
                .como(EntrypointEnum.API_REST);

        var evento = extrairEvento(etapa);

        assertEquals("API_REST", evento.entrypoint());
    }

    private static LogEvento extrairEvento(LogEtapas.EtapaOpcional etapa) throws Exception {
        var metodo = Log.class.getDeclaredMethod("criarEvento");
        metodo.setAccessible(true);
        return (LogEvento) metodo.invoke(etapa);
    }
}
