package org.pipesor.pipeline.catalog.application.ports;

import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

public interface PipelineSettingsStore {
    PipelineView lock(UUID id);
    PipelineView update(ConfigurePipelineCommand command);
}
