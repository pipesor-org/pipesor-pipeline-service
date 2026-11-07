package org.pipesor.pipeline.incident.application.ports;

import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.execution.domain.model.valueobjects.ExecutionOutcome;

public interface IncidentOriginLookup {
    Optional<ExecutionOutcome> lockExecution(UUID id);
}
