package org.pipesor.pipeline.incident.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VerificationExecution(UUID id, UUID pipelineId, Instant startedAt,
                                    String status, String conclusion, boolean failed) {
    public VerificationExecution {
        Objects.requireNonNull(id, "execution id is required");
        Objects.requireNonNull(pipelineId, "pipeline id is required");
    }

    public boolean successful() {
        return !failed && "completed".equals(status) && "success".equals(conclusion);
    }
}
