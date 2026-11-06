package org.pipesor.pipeline.configuration;

import org.pipesor.pipeline.catalog.application.ports.RepositoryLookup;
import org.pipesor.pipeline.catalog.application.queryhandlers.GetRepositoryByIdQueryHandler;
import org.pipesor.pipeline.catalog.application.services.RepositoryQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    @Bean
    RepositoryQueryService repositoryQueryService(RepositoryLookup repositories) {
        return new GetRepositoryByIdQueryHandler(repositories);
    }
}
