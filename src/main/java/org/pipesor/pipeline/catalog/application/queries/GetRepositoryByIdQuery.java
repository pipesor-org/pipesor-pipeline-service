package org.pipesor.pipeline.catalog.application.queries;

import java.util.Objects;
import java.util.UUID;

public record GetRepositoryByIdQuery(UUID repositoryId) {

    public GetRepositoryByIdQuery {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
    }
}
