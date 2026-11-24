package org.pipesor.pipeline.incident.application.queries;

/** A queue, summary or Insights filter value outside the contract (problem code {@code INVALID_FILTER}). */
public final class InvalidIncidentFilter extends IllegalArgumentException {
    public InvalidIncidentFilter(String message) { super(message); }
}
