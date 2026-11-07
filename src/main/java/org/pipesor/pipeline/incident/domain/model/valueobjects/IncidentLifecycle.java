package org.pipesor.pipeline.incident.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record IncidentLifecycle(IncidentStatus status, Instant remediationStartedAt,
                                Instant verificationStartedAt, Instant resolvedAt,
                                Set<UUID> verificationExecutionIds) {
    public IncidentLifecycle {
        Objects.requireNonNull(status, "status is required");
        verificationExecutionIds = Set.copyOf(verificationExecutionIds);
    }

    public static IncidentLifecycle open() {
        return new IncidentLifecycle(IncidentStatus.OPEN, null, null, null, Set.of());
    }
}
