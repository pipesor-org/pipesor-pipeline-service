package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.queries.IncidentQueuePage;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;

public interface IncidentQueueService {
    IncidentQueuePage handle(ListIncidentQueueQuery query);
}
