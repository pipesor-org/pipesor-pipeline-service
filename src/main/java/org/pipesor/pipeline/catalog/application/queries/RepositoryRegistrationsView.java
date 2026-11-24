package org.pipesor.pipeline.catalog.application.queries;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-028: existence of the monitored workflows of a repository, without operational data. {@code accessible} is true
 * when the reader already has an effective capability on that pipeline; otherwise the reader may ask for access.
 */
public record RepositoryRegistrationsView(String repositoryId, List<Registration> items) {
    public RepositoryRegistrationsView {
        Objects.requireNonNull(repositoryId);
        items = List.copyOf(items);
    }

    public record Registration(UUID pipelineId, String githubWorkflowId, boolean accessible) {
        public Registration {
            Objects.requireNonNull(pipelineId);
            Objects.requireNonNull(githubWorkflowId);
        }
    }
}
