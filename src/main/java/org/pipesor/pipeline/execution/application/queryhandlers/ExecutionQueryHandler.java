package org.pipesor.pipeline.execution.application.queryhandlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.ports.ExecutionLookup;
import org.pipesor.pipeline.execution.application.queries.ExecutionPipelinesQuery;
import org.pipesor.pipeline.execution.application.queries.ExecutionStageView;
import org.pipesor.pipeline.execution.application.queries.ExecutionView;
import org.pipesor.pipeline.execution.application.queries.GetExecutionQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionStagesQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionsQuery;
import org.pipesor.pipeline.execution.application.services.ExecutionQueryService;

public class ExecutionQueryHandler implements ExecutionQueryService {
    private final ExecutionLookup lookup;

    public ExecutionQueryHandler(ExecutionLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<ExecutionView> handle(GetExecutionQuery query) {
        return lookup.findById(query.id());
    }

    @Override
    public List<ExecutionView> handle(ListExecutionsQuery query) {
        return lookup.list(query.pipelineId(), query.offset(), query.limit());
    }

    @Override
    public List<ExecutionStageView> handle(ListExecutionStagesQuery query) {
        return lookup.stages(query.executionId());
    }
    @Override
    public Map<UUID, UUID> handle(ExecutionPipelinesQuery query) {
        return lookup.pipelineIds(query.executionIds());
    }

}
