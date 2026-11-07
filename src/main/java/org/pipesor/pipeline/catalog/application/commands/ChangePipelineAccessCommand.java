package org.pipesor.pipeline.catalog.application.commands;

import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;

/** grant=true assigns a capability; false revokes that exact capability. */
public record ChangePipelineAccessCommand(UUID pipelineId, String actorRef, String correlationId,
        String principalRef, AccessCapability capability, boolean grant) {
    public ChangePipelineAccessCommand {
        Objects.requireNonNull(pipelineId, "pipelineId is required");
        Objects.requireNonNull(capability, "capability is required");
        if (actorRef == null || actorRef.isBlank() || correlationId == null || correlationId.isBlank()
                || principalRef == null || principalRef.isBlank()) {
            throw new IllegalArgumentException("Actor, target principal and correlation are required");
        }
    }
}
