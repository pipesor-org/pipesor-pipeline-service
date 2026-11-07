package org.pipesor.pipeline.catalog.application.services;

import java.util.List;
import java.util.Optional;
import org.pipesor.pipeline.catalog.application.queries.GetPipelineQuery;
import org.pipesor.pipeline.catalog.application.queries.ListPipelinesQuery;
import org.pipesor.pipeline.catalog.application.queries.ListVisiblePipelinesQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public interface PipelineQueryService {
    List<PipelineView> handle(ListVisiblePipelinesQuery query);
    Optional<PipelineView> handle(GetPipelineQuery query);
    List<PipelineView> handle(ListPipelinesQuery query);
}
