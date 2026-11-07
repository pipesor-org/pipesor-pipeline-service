package org.pipesor.pipeline.incident.application.commands;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

public record OpenIncidentCommand(UUID pipelineId, UUID executionId, PipelineStage stage,
                                  String summary, Instant detectedAt, String correlationId) {
    public OpenIncidentCommand {
        Objects.requireNonNull(pipelineId, "pipelineId is required");
        Objects.requireNonNull(executionId, "executionId is required");
        Objects.requireNonNull(stage, "stage is required");
        Objects.requireNonNull(summary, "summary is required");
        Objects.requireNonNull(detectedAt, "detectedAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        if (summary.isBlank() || correlationId.isBlank()) {
            throw new IllegalArgumentException("summary and correlationId must not be blank");
        }
    }
}
