package org.pipesor.pipeline.catalog.application.ports;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.RegisterPipelineCommand;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public interface PipelineRegistrationStore {
    PipelineView register(RegisterPipelineCommand command, GitHubConnector.Workflow verified,
                          UUID repositoryId, UUID pipelineId, Instant now);
}
