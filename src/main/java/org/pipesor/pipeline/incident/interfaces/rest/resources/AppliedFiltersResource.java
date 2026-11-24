package org.pipesor.pipeline.incident.interfaces.rest.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

/**
 * Normalized echo of the criteria the server applied (deduplicated, sorted; {@code []} when absent). Clients use its
 * presence to know the server filtered, because unknown query parameters are ignored.
 */
@Schema(description = "Criteria applied by the server (ADR-027)")
public record AppliedFiltersResource(
        ListIncidentQueueQuery.State state,
        List<UUID> pipelineId,
        List<String> repositoryId,
        List<IncidentFilter.SeverityFilter> severity,
        List<PipelineStage> stage,
        List<IncidentStatus> status,
        List<IncidentFilter.DiagnosisFilter> diagnosis,
        @Schema(nullable = true, types = {"string", "null"}, description = "Trimmed text query") String q,
        @Schema(nullable = true, types = {"string", "null"}, description = "How q was matched") IncidentFilter.TextMatch qMatch,
        @Schema(nullable = true, types = {"string", "null"}, format = "date-time") Instant resolvedFrom,
        @Schema(nullable = true, types = {"string", "null"}, format = "date-time") Instant resolvedTo,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(allowableValues = {"createdAt", "resolvedAt"}, description = "Queue order (list only)") String sort) {

    /** Echo for the summary, which has no order. */
    public static AppliedFiltersResource of(IncidentFilter filter) {
        return of(filter, null);
    }

    public static AppliedFiltersResource of(IncidentFilter filter, ListIncidentQueueQuery.Sort sort) {
        var q = filter.q();
        return new AppliedFiltersResource(filter.state(), filter.pipelineIds(), filter.repositoryIds(), filter.severities(),
                filter.stages(), filter.statuses(), filter.diagnoses(), q == null ? null : q.value(), q == null ? null : q.match(),
                filter.resolvedFrom(), filter.resolvedTo(), sort == null ? null : sort.token());
    }
}
