package org.pipesor.pipeline.catalog.application.services;

import java.util.List;
import org.pipesor.pipeline.catalog.application.queries.PipelineActivityView;
import org.pipesor.pipeline.catalog.application.queries.PipelineAdministrationQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineGrantView;

public interface PipelineAdministrationQueryService {
    List<PipelineGrantView> grants(PipelineAdministrationQuery query);
    List<PipelineActivityView> activities(PipelineAdministrationQuery query);
}
