package org.pipesor.pipeline.incident.application.queries;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentRunRole;

public record IncidentRunView(
        UUID id,
        UUID incidentId,
        UUID executionId,
        IncidentRunRole role,
        String correlationId,
        Instant createdAt) {
}
