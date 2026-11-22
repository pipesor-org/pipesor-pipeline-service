package org.pipesor.pipeline.incident.application.queries;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.incident.domain.model.valueobjects.Severity;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

public record IncidentView(
        UUID id,
        UUID pipelineId,
        IncidentStatus status,
        Severity severity,
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
        long displayNumber) {
}
