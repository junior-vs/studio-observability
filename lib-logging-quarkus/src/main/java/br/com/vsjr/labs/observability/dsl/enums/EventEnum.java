package br.com.vsjr.labs.observability.dsl.enums;

import br.com.vsjr.labs.observability.dsl.Event;

public enum EventEnum implements Event {

    EVENTO_GENERICO("evento_generico"),
    CONTEXT_TRACE("context_trace"),
    ERROR_GENERIC("error_generic"),
    EVENT_ERROR("event_error"),
    LOGIN("login"),
    LOGOUT("logout");



    private final String event;

    private EventEnum(String event) {
        this.event = event;
    }

    @Override
    public String getEvent() {
        return event;
    }

}
