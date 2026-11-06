package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface SpringDataGitHubRepository extends Repository<GitHubRepositoryJpaEntity, UUID> {

    Optional<GitHubRepositoryJpaEntity> findById(UUID id);
}
