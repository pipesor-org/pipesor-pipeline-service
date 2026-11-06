package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.ports.RepositoryLookup;
import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaRepositoryLookup implements RepositoryLookup {

    private final SpringDataGitHubRepository repositories;

    public JpaRepositoryLookup(SpringDataGitHubRepository repositories) {
        this.repositories = repositories;
    }

    @Override
    public Optional<GitHubRepository> findById(UUID id) {
        return repositories.findById(id).map(RepositoryEntityTransformer::toDomain);
    }
}
