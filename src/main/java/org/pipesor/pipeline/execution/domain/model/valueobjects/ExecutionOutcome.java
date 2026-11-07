package org.pipesor.pipeline.execution.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

public record ExecutionOutcome(UUID executionId, UUID pipelineId, boolean failed) {

    public ExecutionOutcome {
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(pipelineId, "pipelineId is required");
    }
}
