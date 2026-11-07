package org.pipesor.pipeline.catalog.application.queryhandlers;

import java.util.List;
import org.pipesor.pipeline.catalog.application.ports.PipelineAdministrationLookup;
import org.pipesor.pipeline.catalog.application.queries.PipelineActivityView;
import org.pipesor.pipeline.catalog.application.queries.PipelineAdministrationQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineGrantView;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.application.services.PipelineAdministrationQueryService;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;

public final class PipelineAdministrationQueryHandler implements PipelineAdministrationQueryService {
    private final HumanPipelineAccess access;
    private final PipelineAdministrationLookup lookup;
    public PipelineAdministrationQueryHandler(HumanPipelineAccess access, PipelineAdministrationLookup lookup) {
        this.access = access; this.lookup = lookup;
    }
    @Override
    public List<PipelineGrantView> grants(PipelineAdministrationQuery query) {
        access.require(query.actorRef(), query.pipelineId(), AccessCapability.CONFIGURE);
        return lookup.grants(query.pipelineId(), query.offset(), query.limit());
    }
    @Override
    public List<PipelineActivityView> activities(PipelineAdministrationQuery query) {
        access.require(query.actorRef(), query.pipelineId(), AccessCapability.CONFIGURE);
        return lookup.activities(query.pipelineId(), query.offset(), query.limit());
    }
}
