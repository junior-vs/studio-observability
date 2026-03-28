package br.com.vsjr.labs.observability.dsl;

import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import br.com.vsjr.labs.observability.core.LogSistematico;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LogSistematicoTest {

    @Test
    void erroDevePropagarThrowableParaORuntimeDeLogging() {
        var logger = org.jboss.logmanager.Logger.getLogger(ServicoTeste.class.getName());
        var nivelOriginal = logger.getLevel();
        var usarHandlersPaiOriginal = logger.getUseParentHandlers();
        var handler = new CapturingHandler();

        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);

        var causa = new IllegalStateException("falha esperada");

        try {
            LogSistematico.registrando("Evento com erro")
                    .em(ServicoTeste.class, "processar")
                    .erro(causa);

            assertEquals(1, handler.records().size());
            var record = handler.records().getFirst();
            assertEquals("Evento com erro", record.getMessage());
            assertSame(causa, record.getThrown());
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(usarHandlersPaiOriginal);
            logger.setLevel(nivelOriginal);
        }
    }

    private static final class CapturingHandler extends ExtHandler {
        private final List<ExtLogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        protected void doPublish(ExtLogRecord record) {
            records.add(record);
        }

        List<ExtLogRecord> records() {
            return records;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class ServicoTeste {
        private ServicoTeste() {
        }
    }
}

