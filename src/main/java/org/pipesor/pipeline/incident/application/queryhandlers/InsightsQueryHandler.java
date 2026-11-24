package org.pipesor.pipeline.incident.application.queryhandlers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.pipesor.pipeline.incident.application.ports.IncidentQueueLookup;
import org.pipesor.pipeline.incident.application.queries.IncidentFilter.DiagnosisFilter;
import org.pipesor.pipeline.incident.application.queries.InsightsQuery;
import org.pipesor.pipeline.incident.application.queries.InsightsView;
import org.pipesor.pipeline.incident.application.services.InsightsService;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

/**
 * History Insights over every visible resolved incident in the window (ADR-027), with the definitions the dashboard
 * computed in the browser: population by {@code resolvedAt} in {@code [from, to]}; diagnosis time
 * {@code diagnosis.createdAt - detectedAt} (negative samples ignored); MTTR {@code resolvedAt - detectedAt}; confirmation
 * rate over human reviews only; 7-day buckets from {@code from}, the last one ending at {@code to}.
 */
public final class InsightsQueryHandler implements InsightsService {
    static final Duration BUCKET = Duration.ofDays(7);
    private final Function<String, Set<String>> visibleRepositories;
    private final IncidentQueueLookup lookup;

    public InsightsQueryHandler(Function<String, Set<String>> visibleRepositories, IncidentQueueLookup lookup) {
        this.visibleRepositories = Objects.requireNonNull(visibleRepositories);
        this.lookup = Objects.requireNonNull(lookup);
    }

    @Override
    public InsightsView handle(InsightsQuery query) {
        var aggregate = new Aggregate(query);
        var repositories = visibleRepositories.apply(query.actorRef());
        if (!repositories.isEmpty()) {
            lookup.resolved(query.actorRef(), Set.copyOf(repositories), query.from(), query.to(), aggregate::add);
        }
        return aggregate.view();
    }

    private static final class Aggregate {
        private final InsightsQuery query;
        private final long fromMs;
        private final long toMs;
        private final long bucketMs = BUCKET.toMillis();
        private long resolved;
        private final EnumMap<DiagnosisFilter, Long> outcomes = new EnumMap<>(DiagnosisFilter.class);
        private final Samples diagnosis = new Samples();
        private final Samples resolution = new Samples();
        private final EnumMap<PipelineStage, Long> stages = new EnumMap<>(PipelineStage.class);
        private final LinkedHashMap<String, InsightsView.RecurringContext> contexts = new LinkedHashMap<>();
        private final long[] bucketCounts;
        private final Samples[] bucketSamples;

        Aggregate(InsightsQuery query) {
            this.query = query;
            this.fromMs = query.from().toEpochMilli();
            this.toMs = query.to().toEpochMilli();
            for (var value : DiagnosisFilter.values()) outcomes.put(value, 0L);
            int buckets = (int) Math.max(1, Math.ceilDiv(toMs - fromMs, bucketMs));
            bucketCounts = new long[buckets];
            bucketSamples = new Samples[buckets];
            for (int index = 0; index < buckets; index++) bucketSamples[index] = new Samples();
        }

        void add(InsightsView.Row row) {
            long resolvedMs = row.resolvedAt().toEpochMilli();
            if (resolvedMs < fromMs || resolvedMs > toMs) return;
            resolved++;
            outcomes.merge(row.outcome(), 1L, Long::sum);
            Long diagnosisMs = null;
            if (row.diagnosisCreatedAt() != null) {
                long value = row.diagnosisCreatedAt().toEpochMilli() - row.detectedAt().toEpochMilli();
                if (value >= 0) { diagnosisMs = value; diagnosis.add(value); }
            }
            long mttr = resolvedMs - row.detectedAt().toEpochMilli();
            if (mttr >= 0) resolution.add(mttr);
            stages.merge(row.stage(), 1L, Long::sum);
            contexts.merge(row.pipelineId() + ":" + row.stage(),
                    new InsightsView.RecurringContext(row.pipelineId(), row.repository(), row.workflow(), row.stage(), 1),
                    (previous, next) -> new InsightsView.RecurringContext(previous.pipelineId(), previous.repository(),
                            previous.workflow(), previous.stage(), previous.count() + 1));
            int index = (int) Math.min(bucketCounts.length - 1, Math.max(0, Math.floorDiv(resolvedMs - fromMs, bucketMs)));
            bucketCounts[index]++;
            if (diagnosisMs != null) bucketSamples[index].add(diagnosisMs);
        }

        InsightsView view() {
            long confirmed = outcomes.get(DiagnosisFilter.CONFIRMED);
            long rejected = outcomes.get(DiagnosisFilter.CORRECTED) + outcomes.get(DiagnosisFilter.REJECTED);
            long validated = confirmed + rejected;
            var summary = new InsightsView.Summary(resolved, confirmed, rejected, outcomes.get(DiagnosisFilter.PENDING),
                    validated == 0 ? null : (double) confirmed / validated, diagnosis.mean(), resolution.mean());
            var stageCounts = stages.entrySet().stream().map(entry -> new InsightsView.StageCount(entry.getKey(), entry.getValue()))
                    .sorted(Comparator.comparingLong(InsightsView.StageCount::count).reversed()
                            .thenComparing(InsightsView.StageCount::stage)).toList();
            var recurring = contexts.values().stream().sorted(Comparator
                            .comparingLong(InsightsView.RecurringContext::count).reversed()
                            .thenComparing(context -> context.repository().fullName(), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(context -> context.workflow().name(), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(InsightsView.RecurringContext::stage)
                            .thenComparing(context -> context.pipelineId().toString()))
                    .limit(query.top()).toList();
            var trend = new ArrayList<InsightsView.TrendBucket>();
            for (int index = 0; index < bucketCounts.length; index++) {
                long start = fromMs + index * bucketMs;
                long end = Math.min(start + bucketMs, toMs);
                trend.add(new InsightsView.TrendBucket(java.time.Instant.ofEpochMilli(start), java.time.Instant.ofEpochMilli(end),
                        bucketCounts[index], bucketSamples[index].mean().meanMs()));
            }
            return new InsightsView(query.from(), query.to(), summary, outcomes, stageCounts, recurring, List.copyOf(trend));
        }
    }

    private static final class Samples {
        private long count;
        private long sum;

        void add(long value) { count++; sum = Math.addExact(sum, value); }

        InsightsView.Mean mean() {
            return new InsightsView.Mean(count == 0 ? null : Math.round((double) sum / count), count);
        }
    }
}
