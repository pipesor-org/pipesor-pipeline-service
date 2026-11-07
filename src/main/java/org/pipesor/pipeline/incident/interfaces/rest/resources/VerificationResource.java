package org.pipesor.pipeline.incident.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record VerificationResource(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID executionId) {
    public VerificationResource {
        if (executionId == null) { throw new IllegalArgumentException("executionId is required"); }
    }
}
