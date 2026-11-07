package org.pipesor.pipeline.incident.application.ports;

import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.domain.model.valueobjects.VerificationExecution;

public interface VerificationLookup {
    Optional<VerificationExecution> lockVerification(UUID id);
}
