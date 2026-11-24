package org.pipesor.pipeline.incident.application.queries;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.Severity;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

public record IncidentView(
        UUID id,
        UUID pipelineId,
        IncidentStatus status,
        /** Initial severity from the pipeline's criticality when opened; null when unclassified. */
        @Schema(nullable = true, types = {"string", "null"}) Severity severity,
        PipelineStage stage,
        String summary,
        String assigneeRef,
        String assigneeDisplayNameSnapshot,
        Instant detectedAt,
        Instant remediationStartedAt,
        Instant verificationStartedAt,
        Instant resolvedAt,
        String correlationId,
        Instant createdAt,
        /** Human-friendly number shown as {@code INC-<n>}; presentation only, the UUID stays the identity. */
        long displayNumber,
        RepositoryRef repository,
        WorkflowRef workflow,
        /** Null while no admitted diagnosis is projected (ADR-027). */
        @Schema(nullable = true, types = {"object", "null"}) DiagnosisSummary diagnosis) {
}
