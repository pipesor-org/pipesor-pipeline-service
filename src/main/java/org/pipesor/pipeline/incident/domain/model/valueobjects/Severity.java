package org.pipesor.pipeline.incident.domain.model.valueobjects;

public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW;

    /**
     * MVP rule: an incident's initial severity is the criticality of its pipeline when it is opened (same
     * scale). Criticality stays a pipeline property; the incident keeps its own copy from that moment.
     */
    public static Severity initialFor(String pipelineCriticality) {
        return pipelineCriticality == null ? null : valueOf(pipelineCriticality);
    }
}
