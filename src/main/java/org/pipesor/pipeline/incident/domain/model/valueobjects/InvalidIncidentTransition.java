package org.pipesor.pipeline.incident.domain.model.valueobjects;

public final class InvalidIncidentTransition extends IllegalArgumentException {
    public InvalidIncidentTransition(String message) { super(message); }
}
