package org.pipesor.pipeline.incident.application.queries;

import java.util.UUID;

public record ListIncidentsQuery(UUID pipelineId, int offset, int limit) {
    public ListIncidentsQuery {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 100");
        }
    }
}
