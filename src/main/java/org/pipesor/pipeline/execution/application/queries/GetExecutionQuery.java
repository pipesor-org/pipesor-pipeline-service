package org.pipesor.pipeline.execution.application.queries;

import java.util.Objects;
import java.util.UUID;

public record GetExecutionQuery(UUID id) {
    public GetExecutionQuery {
        Objects.requireNonNull(id, "id is required");
    }
}
