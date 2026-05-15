package br.com.vsjr.labs.observability.dsl.enums;

import br.com.vsjr.labs.observability.dsl.Entrypoint;

/**
 * Pontos de entrada padrao fornecidos pela biblioteca.
 */
public enum EntrypointEnum implements Entrypoint {

    API_REST("API_REST"),
    KAFKA_CONSUMER("KAFKA_CONSUMER"),
    SCHEDULER("SCHEDULER"),
    GRPC("GRPC"),
    INTERNO("INTERNO");

    private final String entrypoint;

    EntrypointEnum(String entrypoint) {
        this.entrypoint = entrypoint;
    }

    @Override
    public String getEntrypoint() {
        return entrypoint;
    }
}
