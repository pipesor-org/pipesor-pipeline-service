package org.pipesor.pipeline.catalog.application.queries;

public record ListVisiblePipelinesQuery(String actorRef, int offset, int limit) {
    public ListVisiblePipelinesQuery {
        if (actorRef == null || actorRef.isBlank() || offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Actor, nonnegative offset and limit 1..100 required");
        }
    }
}
