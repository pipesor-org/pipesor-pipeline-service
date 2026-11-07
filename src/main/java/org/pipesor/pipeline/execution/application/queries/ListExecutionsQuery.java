package org.pipesor.pipeline.execution.application.queries;

import java.util.UUID;

public record ListExecutionsQuery(UUID pipelineId, int offset, int limit) {
    public ListExecutionsQuery {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 100");
        }
    }
}
