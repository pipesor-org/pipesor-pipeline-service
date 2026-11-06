package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;

final class RepositoryEntityTransformer {

    private RepositoryEntityTransformer() {
    }

    static GitHubRepository toDomain(GitHubRepositoryJpaEntity entity) {
        return new GitHubRepository(entity.getId(), entity.getGithubRepositoryId(),
                entity.getFullName(), entity.getDefaultBranch(), entity.getCreatedAt());
    }
}
