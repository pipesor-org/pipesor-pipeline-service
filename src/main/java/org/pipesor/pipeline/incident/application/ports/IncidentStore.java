package org.pipesor.pipeline.incident.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.aggregates.Incident;

public interface IncidentStore {
    void save(Incident incident, UUID originRunId);
    Optional<Incident> lock(UUID id);
    void update(Incident incident, UUID verificationId, UUID linkId, String correlationId, Instant now);
}
