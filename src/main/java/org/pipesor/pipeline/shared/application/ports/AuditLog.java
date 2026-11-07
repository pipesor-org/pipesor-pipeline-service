package org.pipesor.pipeline.shared.application.ports;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AuditLog {
    void append(UUID id, String entityType, UUID entityId, String action, String actorRef,
                String correlationId, Instant now, Map<String, String> before, Map<String, String> after);
}
