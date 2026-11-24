package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.queries.IncidentSummaryQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentSummaryView;

public interface IncidentSummaryService {
    IncidentSummaryView handle(IncidentSummaryQuery query);
}
