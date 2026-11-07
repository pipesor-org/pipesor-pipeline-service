package org.pipesor.pipeline.execution.application.services;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.queries.ExecutionPipelinesQuery;
import org.pipesor.pipeline.execution.application.queries.ExecutionStageView;
import org.pipesor.pipeline.execution.application.queries.ExecutionView;
import org.pipesor.pipeline.execution.application.queries.GetExecutionQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionStagesQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionsQuery;

public interface ExecutionQueryService {
    Map<UUID, UUID> handle(ExecutionPipelinesQuery query);
    Optional<ExecutionView> handle(GetExecutionQuery query);
    List<ExecutionView> handle(ListExecutionsQuery query);
    List<ExecutionStageView> handle(ListExecutionStagesQuery query);
}
