package org.pipesor.pipeline.catalog.application.ports;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;

public interface PipelineGrantStore {
    boolean change(ChangePipelineAccessCommand command, UUID grantId, Instant now);
}
