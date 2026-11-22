package org.pipesor.pipeline.incident.domain.model.valueobjects;

/** An incident without its single failed ORIGIN execution violates DD-002; never a client error. */
public final class IncidentOriginMissing extends IllegalStateException {
    public IncidentOriginMissing() { super("Incident ORIGIN execution missing"); }
}
