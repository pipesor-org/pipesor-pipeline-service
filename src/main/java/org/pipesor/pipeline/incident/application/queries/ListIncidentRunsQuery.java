package org.pipesor.pipeline.incident.application.queries;

import java.util.Objects;
import java.util.UUID;

public record ListIncidentRunsQuery(UUID incidentId, int offset, int limit) {
    public ListIncidentRunsQuery {
        Objects.requireNonNull(incidentId, "incidentId is required");
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 100");
        }
    }
}
