package br.com.vsjr.labs.observability.dsl.enums;

import br.com.vsjr.labs.observability.dsl.Event;

public enum EventError implements Event {

    EVENT_ERROR("Evento_ERRO"),
    ERROR_GENERIC("Erro_Geral");


    private final String event;

    private EventError(String event) {
        this.event = event;
    }
    @Override
    public String getEvent() {
        return event;
    }

}
