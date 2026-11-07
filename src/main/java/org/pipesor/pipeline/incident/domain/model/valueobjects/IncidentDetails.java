package org.pipesor.pipeline.incident.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

public record IncidentDetails(PipelineStage stage, String summary, Instant detectedAt,
                              String correlationId, Instant createdAt) {
    public IncidentDetails {
        Objects.requireNonNull(stage, "stage is required");
        Objects.requireNonNull(summary, "summary is required");
        Objects.requireNonNull(detectedAt, "detectedAt is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (summary.isBlank() || correlationId.isBlank()) {
            throw new IllegalArgumentException("summary and correlationId must not be blank");
        }
    }
}
