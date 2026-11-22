package org.pipesor.pipeline.incident.application.queries;

import java.util.List;

public record IncidentQueuePage(List<IncidentView> items, ListIncidentQueueQuery.Cursor next) {
    public IncidentQueuePage { items = List.copyOf(items); }
}
