package org.pipesor.pipeline.incident.interfaces.rest.resources;

import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.IncidentSummaryView;

/** Counts of the filtered, visible incident set; every map sums to {@code total} (ADR-027). */
public record IncidentSummaryResource(long total, CountResources.BySeverity bySeverity, CountResources.ByStatus byStatus,
                                      CountResources.ByDiagnosis byDiagnosis, AppliedFiltersResource appliedFilters) {
    public static IncidentSummaryResource of(IncidentSummaryView view, IncidentFilter filter) {
        return new IncidentSummaryResource(view.total(), CountResources.BySeverity.of(view.bySeverity()),
                CountResources.ByStatus.of(view.byStatus()), CountResources.ByDiagnosis.of(view.byDiagnosis()),
                AppliedFiltersResource.of(filter));
    }
}
