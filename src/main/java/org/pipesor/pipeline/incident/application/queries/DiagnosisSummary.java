package org.pipesor.pipeline.incident.application.queries;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.pipesor.pipeline.incident.domain.model.valueobjects.DiagnosisStatus;

/**
 * Summary of the incident's current diagnosis projected from Recommendation (ADR-027); the full diagnosis stays there.
 * {@code probableCause} is inferred model output, at most 1000 characters.
 */
public record DiagnosisSummary(
        DiagnosisStatus status,
        @Schema(nullable = true, types = {"string", "null"}, maxLength = 1000, description = "Inferred by the model") String probableCause,
        Instant createdAt) {
}
