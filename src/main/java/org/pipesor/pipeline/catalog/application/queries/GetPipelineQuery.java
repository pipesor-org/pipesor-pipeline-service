package org.pipesor.pipeline.catalog.application.queries;

import java.util.Objects;
import java.util.UUID;

public record GetPipelineQuery(UUID id) {
    public GetPipelineQuery {
        Objects.requireNonNull(id, "id is required");
    }
}
