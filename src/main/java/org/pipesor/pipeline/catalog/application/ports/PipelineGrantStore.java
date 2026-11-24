package org.pipesor.pipeline.catalog.application.ports;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;

public interface PipelineGrantStore {
    /** Serializes the grant changes of one pipeline until the current transaction ends. */
    void lock(UUID pipelineId);

    /** Principals holding the exact capability on the pipeline. */
    Set<String> holders(UUID pipelineId, AccessCapability capability);

    boolean change(ChangePipelineAccessCommand command, UUID grantId, Instant now);
}
