package org.pipesor.pipeline.execution.application.queries;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

public record ExecutionStageView(
        UUID id,
        UUID executionId,
        PipelineStage stage,
        String externalStatus,
        Instant startedAt,
        Instant completedAt,
        String correlationId,
        Instant createdAt) {
}
