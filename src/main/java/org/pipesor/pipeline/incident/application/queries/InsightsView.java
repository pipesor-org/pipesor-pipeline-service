package org.pipesor.pipeline.incident.application.queries;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

/**
 * History Insights (ADR-027), with the definitions the dashboard used: mean diagnosis time is
 * {@code diagnosis.createdAt - detectedAt}; MTTR is {@code resolvedAt - detectedAt}; the confirmation rate is
 * {@code confirmed / (confirmed + rejected)} where rejected counts CORRECTED and REJECTED. Means are null without samples.
 */
public record InsightsView(Instant from, Instant to, Summary summary, Map<IncidentFilter.DiagnosisFilter, Long> outcomes,
                           List<StageCount> stages, List<RecurringContext> recurring, List<TrendBucket> trend) {
    public InsightsView {
        outcomes = Collections.unmodifiableMap(new EnumMap<>(outcomes));
        stages = List.copyOf(stages);
        recurring = List.copyOf(recurring);
        trend = List.copyOf(trend);
    }

    public record Summary(long resolvedIncidents, long confirmed, long rejected, long pending, Double confirmationRate,
                          Mean meanDiagnosis, Mean meanResolution) {}

    public record Mean(Long meanMs, long sampleSize) {}

    public record StageCount(PipelineStage stage, long count) {}

    public record RecurringContext(UUID pipelineId, RepositoryRef repository, WorkflowRef workflow, PipelineStage stage, long count) {}

    public record TrendBucket(Instant start, Instant end, long incidentCount, Long meanDiagnosisMs) {}

    /** One visible resolved incident in the window, as read for the aggregation. */
    public record Row(UUID incidentId, UUID pipelineId, PipelineStage stage, Instant detectedAt, Instant resolvedAt,
                      RepositoryRef repository, WorkflowRef workflow, IncidentFilter.DiagnosisFilter outcome,
                      Instant diagnosisCreatedAt) {}
}
