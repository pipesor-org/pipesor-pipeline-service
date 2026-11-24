package org.pipesor.pipeline.incident.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.pipesor.pipeline.incident.application.queries.InsightsView;

/** History Insights computed by the server over visible resolved incidents (ADR-027). */
public record InsightsResource(Window window, Summary summary, CountResources.ByDiagnosis outcomes,
                               List<InsightsView.StageCount> stages, List<InsightsView.RecurringContext> recurring,
                               List<Trend> trend) {
    public record Window(Instant from, Instant to) {}

    public record Summary(long resolvedIncidents, long confirmed,
                          @Schema(description = "CORRECTED plus REJECTED reviews") long rejected, long pending,
                          @Schema(nullable = true, types = {"number", "null"}, description = "confirmed / (confirmed + rejected)") Double confirmationRate,
                          Mean meanDiagnosis, Mean meanResolution) {}

    public record Mean(@Schema(nullable = true, types = {"integer", "null"}) Long meanMs, long sampleSize) {}

    public record Trend(Instant start, Instant end, long incidentCount,
                        @Schema(nullable = true, types = {"integer", "null"}) Long meanDiagnosisMs) {}

    public static InsightsResource of(InsightsView view) {
        var summary = view.summary();
        return new InsightsResource(new Window(view.from(), view.to()),
                new Summary(summary.resolvedIncidents(), summary.confirmed(), summary.rejected(), summary.pending(),
                        summary.confirmationRate(), mean(summary.meanDiagnosis()), mean(summary.meanResolution())),
                CountResources.ByDiagnosis.of(view.outcomes()), view.stages(), view.recurring(),
                view.trend().stream().map(bucket -> new Trend(bucket.start(), bucket.end(), bucket.incidentCount(),
                        bucket.meanDiagnosisMs())).toList());
    }

    private static Mean mean(InsightsView.Mean mean) { return new Mean(mean.meanMs(), mean.sampleSize()); }
}
