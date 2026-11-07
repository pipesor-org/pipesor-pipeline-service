package org.pipesor.pipeline.catalog.application.queries;

import java.util.UUID;

public record ListPipelinesQuery(UUID repositoryId, int offset, int limit) {
    public ListPipelinesQuery {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 100");
        }
    }
}
