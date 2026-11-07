package org.pipesor.pipeline.execution.application.ports;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.queries.ExecutionStageView;
import org.pipesor.pipeline.execution.application.queries.ExecutionView;

public interface ExecutionLookup {
    Map<UUID, UUID> pipelineIds(Set<UUID> executionIds);
    Optional<ExecutionView> findById(UUID id);
    List<ExecutionView> list(UUID pipelineId, int offset, int limit);
    List<ExecutionStageView> stages(UUID executionId);
}
