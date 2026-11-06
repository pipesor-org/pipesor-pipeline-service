package org.pipesor.pipeline.catalog.application.queryhandlers;

import java.util.Optional;
import org.pipesor.pipeline.catalog.application.ports.RepositoryLookup;
import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;
import org.pipesor.pipeline.catalog.application.queries.GetRepositoryByIdQuery;
import org.pipesor.pipeline.catalog.application.services.RepositoryQueryService;

public class GetRepositoryByIdQueryHandler implements RepositoryQueryService {

    private final RepositoryLookup repositories;

    public GetRepositoryByIdQueryHandler(RepositoryLookup repositories) {
        this.repositories = repositories;
    }

    @Override
    public Optional<GitHubRepository> handle(GetRepositoryByIdQuery query) {
        return repositories.findById(query.repositoryId());
    }
}
