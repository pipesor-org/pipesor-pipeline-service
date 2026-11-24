package org.pipesor.pipeline.catalog.application.queryhandlers;

import java.util.List;
import java.util.Optional;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.queries.GetPipelineQuery;
import org.pipesor.pipeline.catalog.application.queries.ListPipelinesQuery;
import org.pipesor.pipeline.catalog.application.queries.ListVisiblePipelinesQuery;
import org.pipesor.pipeline.catalog.application.queries.ListRepositoryRegistrationsQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.queries.RepositoryRegistrationsView;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.application.services.PipelineQueryService;

public class PipelineQueryHandler implements PipelineQueryService {
    private final PipelineLookup lookup;
    private final HumanPipelineAccess human;

    public PipelineQueryHandler(PipelineLookup lookup, HumanPipelineAccess human) {
        this.lookup = lookup;
        this.human = human;
    }

    @Override
    public Optional<PipelineView> handle(GetPipelineQuery query) {
        return lookup.findById(query.id());
    }

    @Override
    public List<PipelineView> handle(ListPipelinesQuery query) {
        return lookup.list(query.repositoryId(), query.offset(), query.limit());
    }
    @Override
    public List<PipelineView> handle(ListVisiblePipelinesQuery query) {
        return human.list(query.actorRef(), query.offset(), query.limit());
    }
    @Override
    public RepositoryRegistrationsView handle(ListRepositoryRegistrationsQuery query) {
        return human.registrations(query.actorRef(), query.githubRepositoryId());
    }

}
