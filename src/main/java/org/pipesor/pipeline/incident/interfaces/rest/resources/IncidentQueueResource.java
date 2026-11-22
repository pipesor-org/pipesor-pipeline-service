package org.pipesor.pipeline.incident.interfaces.rest.resources;

import java.util.List;
import org.pipesor.pipeline.incident.application.queries.IncidentView;

/** Items use the same shape as {@code /pipelines/{id}/incidents}; {@code nextPageToken} is opaque and nullable. */
public record IncidentQueueResource(List<IncidentView> items, String nextPageToken) {
    public IncidentQueueResource { items = List.copyOf(items); }
}
