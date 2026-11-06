package org.pipesor.pipeline.catalog.application.services;

import java.util.Optional;
import org.pipesor.pipeline.catalog.domain.model.entities.GitHubRepository;
import org.pipesor.pipeline.catalog.application.queries.GetRepositoryByIdQuery;

public interface RepositoryQueryService {

    Optional<GitHubRepository> handle(GetRepositoryByIdQuery query);
}
