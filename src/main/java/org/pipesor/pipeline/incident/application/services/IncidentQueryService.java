package org.pipesor.pipeline.incident.application.services;

import java.util.List;
import java.util.Optional;
import org.pipesor.pipeline.incident.application.queries.GetIncidentQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentRunsQuery;
import org.pipesor.pipeline.incident.application.queries.ListIncidentsQuery;

public interface IncidentQueryService {
    Optional<IncidentView> handle(GetIncidentQuery query);
    List<IncidentView> handle(ListIncidentsQuery query);
    List<IncidentRunView> handle(ListIncidentRunsQuery query);
}
