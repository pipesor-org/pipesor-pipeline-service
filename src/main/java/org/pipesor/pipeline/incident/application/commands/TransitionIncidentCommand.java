package org.pipesor.pipeline.incident.application.commands;

import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentAction;

/** actorRef comes from the authenticated adapter, never from a public request body. */
public record TransitionIncidentCommand(UUID incidentId, IncidentAction action, UUID executionId,
                                         String actorRef, String correlationId) {
    public TransitionIncidentCommand {
        Objects.requireNonNull(incidentId, "incidentId is required");
        Objects.requireNonNull(action, "action is required");
        if (actorRef == null || actorRef.isBlank() || correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("Actor and correlation are required");
        }
        if ((action == IncidentAction.START_REMEDIATION) != (executionId == null)) {
            throw new IllegalArgumentException("Execution is required only for verification-related actions");
        }
    }
}
