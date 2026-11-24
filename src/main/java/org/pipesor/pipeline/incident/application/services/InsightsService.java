package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.queries.InsightsQuery;
import org.pipesor.pipeline.incident.application.queries.InsightsView;

public interface InsightsService {
    InsightsView handle(InsightsQuery query);
}
