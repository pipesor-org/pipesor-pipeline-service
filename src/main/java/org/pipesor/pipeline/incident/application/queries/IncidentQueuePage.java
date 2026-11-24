package org.pipesor.pipeline.incident.application.queries;

import java.util.List;

/** One queue page; {@code total} counts every matching incident the actor may view, independent of the page. */
public record IncidentQueuePage(List<IncidentView> items, ListIncidentQueueQuery.Cursor next, long total) {
    public IncidentQueuePage { items = List.copyOf(items); }
}
