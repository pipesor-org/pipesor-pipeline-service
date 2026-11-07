package org.pipesor.pipeline.catalog.application.services;

import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public interface PipelineConfigurationService {
    PipelineView handle(ConfigurePipelineCommand command);
    boolean handle(ChangePipelineAccessCommand command);
}
