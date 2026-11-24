package org.pipesor.pipeline.incident.application.queries;

import java.util.Objects;

/** Counts of the actor's visible incidents that match {@code filter}, per severity, status and diagnosis. */
public record IncidentSummaryQuery(String actorRef, IncidentFilter filter) {
    public IncidentSummaryQuery { Objects.requireNonNull(actorRef); Objects.requireNonNull(filter); }
}
