package org.pipesor.pipeline.catalog.application.queries;

import java.util.UUID;

public record PipelineAdministrationQuery(UUID pipelineId, String actorRef, int offset, int limit) {
    public PipelineAdministrationQuery {
        if (pipelineId == null || actorRef == null || actorRef.isBlank() || offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Pipeline, actor, nonnegative offset and limit 1..100 required");
        }
    }
}
