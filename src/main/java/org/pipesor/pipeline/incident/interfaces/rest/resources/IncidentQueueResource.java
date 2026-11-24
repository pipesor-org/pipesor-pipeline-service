package org.pipesor.pipeline.incident.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.pipesor.pipeline.incident.application.queries.IncidentView;

/**
 * Items use the same shape as {@code /pipelines/{id}/incidents}; {@code nextPageToken} is opaque, nullable and bound to
 * the criteria; {@code total} counts every matching incident the caller may view (ADR-027).
 */
public record IncidentQueueResource(
        List<IncidentView> items,
        @Schema(nullable = true, types = {"string", "null"}) String nextPageToken,
        long total,
        AppliedFiltersResource appliedFilters) {
    public IncidentQueueResource { items = List.copyOf(items); }
}
