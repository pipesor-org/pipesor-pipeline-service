package org.pipesor.pipeline.catalog.application.ports;

import java.util.List;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.queries.PipelineActivityView;
import org.pipesor.pipeline.catalog.application.queries.PipelineGrantView;

public interface PipelineAdministrationLookup {
    List<PipelineGrantView> grants(UUID pipelineId, int offset, int limit);
    List<PipelineActivityView> activities(UUID pipelineId, int offset, int limit);
}
