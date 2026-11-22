package org.pipesor.pipeline.catalog.application.services;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;

/**
 * Repeats the current monitoring status of every registered pipeline to Ingestion (ADR-023). Pipelines
 * are read in bounded pages, each page in its own short read; no transaction spans a remote call.
 * The PUT is idempotent per status, so concurrent runs on several replicas converge. One instance never
 * overlaps its own runs, and a run stops at the first {@code UNAVAILABLE} outcome so an unreachable
 * Ingestion is not hammered page by page; the next run repeats everything.
 */
public final class CaptureScopeReconciliation {
    /** Reads one page of all pipelines in a stable order. */
    public interface Pages { List<PipelineView> page(int offset, int limit); }

    public enum Status { COMPLETED, COMPLETED_WITH_FAILURES, ABORTED_UNAVAILABLE, FAILED, SKIPPED_RUNNING }

    public record Report(Status status, Instant startedAt, Instant finishedAt, int attempted, int synced,
                         Map<CaptureScopeSync.Outcome, Integer> failures) {
        public Report { failures = Map.copyOf(failures); }
    }

    private final Pages pages;
    private final CaptureScopeSync sync;
    private final int pageSize;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Report> last = new AtomicReference<>();

    public CaptureScopeReconciliation(Pages pages, CaptureScopeSync sync, int pageSize, Clock clock) {
        if (pageSize < 1) throw new IllegalArgumentException("A positive page size is required");
        this.pages = Objects.requireNonNull(pages);
        this.sync = Objects.requireNonNull(sync);
        this.pageSize = pageSize;
        this.clock = Objects.requireNonNull(clock);
    }

    /** Runs one pass unless this instance is already running one; the correlation id is shared by the pass. */
    public Report run(String correlationId) {
        Instant started = clock.instant();
        if (!running.compareAndSet(false, true)) {
            return new Report(Status.SKIPPED_RUNNING, started, started, 0, 0, Map.of());
        }
        int attempted = 0;
        int synced = 0;
        var failures = new EnumMap<CaptureScopeSync.Outcome, Integer>(CaptureScopeSync.Outcome.class);
        Status status = Status.COMPLETED;
        try {
            pass:
            for (int offset = 0; ; offset += pageSize) {
                var page = pages.page(offset, pageSize);
                for (var pipeline : page) {
                    attempted++;
                    var outcome = outcome(pipeline, correlationId);
                    if (outcome == CaptureScopeSync.Outcome.SYNCED) {
                        synced++;
                        continue;
                    }
                    failures.merge(outcome, 1, Integer::sum);
                    if (outcome == CaptureScopeSync.Outcome.UNAVAILABLE) {
                        status = Status.ABORTED_UNAVAILABLE;
                        break pass;
                    }
                }
                if (page.size() < pageSize) break;
            }
            if (status == Status.COMPLETED && !failures.isEmpty()) status = Status.COMPLETED_WITH_FAILURES;
        } catch (RuntimeException readFailure) {
            status = Status.FAILED;
        } finally {
            running.set(false);
        }
        var report = new Report(status, started, clock.instant(), attempted, synced, failures);
        last.set(report);
        return report;
    }

    /** The most recent finished pass, if any. */
    public Report last() { return last.get(); }

    private CaptureScopeSync.Outcome outcome(PipelineView pipeline, String correlationId) {
        CaptureScopeSync.Scope scope;
        try {
            scope = CaptureScopeSync.Scope.of(pipeline);
        } catch (RuntimeException incomplete) {
            return CaptureScopeSync.Outcome.INVALID_REQUEST;
        }
        try {
            return sync.sync(scope, correlationId).outcome();
        } catch (RuntimeException unexpected) {
            return CaptureScopeSync.Outcome.UNAVAILABLE;
        }
    }
}
