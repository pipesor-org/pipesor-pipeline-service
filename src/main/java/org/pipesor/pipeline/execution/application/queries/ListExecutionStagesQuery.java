package org.pipesor.pipeline.execution.application.queries;

import java.util.Objects;
import java.util.UUID;

public record ListExecutionStagesQuery(UUID executionId) {
    public ListExecutionStagesQuery {
        Objects.requireNonNull(executionId, "executionId is required");
    }
}
