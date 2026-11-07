package org.pipesor.pipeline.catalog.application.services;

import org.pipesor.pipeline.catalog.application.commands.RegisterPipelineCommand;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public interface PipelineRegistrationService {
    PipelineView handle(RegisterPipelineCommand command);
}
