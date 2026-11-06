package org.pipesor.pipeline.catalog.application.ports;

import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;

public interface RepositoryLookup {

    Optional<GitHubRepository> findById(UUID id);
}
