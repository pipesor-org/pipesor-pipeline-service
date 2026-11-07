package org.pipesor.pipeline.catalog.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public interface PipelineLookup {
    Set<String> candidateRepositoryIds(String actorRef);
    Optional<PipelineView> findById(UUID id);
    List<PipelineView> listVisible(String actorRef, Set<String> allowedRepositoryIds, int offset, int limit);
    List<PipelineView> list(UUID repositoryId, int offset, int limit);
}
