package org.pipesor.pipeline.incident.application.queryhandlers;

import java.util.List;
import java.util.Optional;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.queries.GetIncidentQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentRunsQuery;
import org.pipesor.pipeline.incident.application.queries.ListIncidentsQuery;
import org.pipesor.pipeline.incident.application.services.IncidentQueryService;

public class IncidentQueryHandler implements IncidentQueryService {
    private final IncidentLookup lookup;

    public IncidentQueryHandler(IncidentLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<IncidentView> handle(GetIncidentQuery query) {
        return lookup.findById(query.id());
    }

    @Override
    public List<IncidentView> handle(ListIncidentsQuery query) {
        return lookup.list(query.pipelineId(), query.offset(), query.limit());
    }

    @Override
    public List<IncidentRunView> handle(ListIncidentRunsQuery query) {
        return lookup.runs(query.incidentId(), query.offset(), query.limit());
    }
}
