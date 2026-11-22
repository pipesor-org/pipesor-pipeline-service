package org.pipesor.pipeline.catalog.application.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync.Outcome;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync.Result;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync.Scope;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureScopeReconciliationTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);
    private final List<PipelineView> pipelines = new ArrayList<>();
    private final List<int[]> pageReads = new CopyOnWriteArrayList<>();
    private final List<Scope> sent = new CopyOnWriteArrayList<>();
    private volatile Function<Scope, Outcome> answer = scope -> Outcome.SYNCED;

    private final CaptureScopeSync sync = (scope, correlation) -> {
        sent.add(scope);
        var outcome = answer.apply(scope);
        return outcome == Outcome.SYNCED ? new Result(outcome, "r", 1L) : Result.failed(outcome);
    };

    private CaptureScopeReconciliation reconciliation(int pageSize) {
        return new CaptureScopeReconciliation((offset, limit) -> {
            pageReads.add(new int[] {offset, limit});
            return pipelines.subList(Math.min(offset, pipelines.size()), Math.min(offset + limit, pipelines.size()));
        }, sync, pageSize, CLOCK);
    }

    @Test
    void readsAllPipelinesInBoundedPagesAndSendsTheirCurrentStatus() {
        for (int i = 0; i < 7; i++) pipelines.add(pipeline(i, i % 2 == 0 ? MonitoringStatus.ACTIVE : MonitoringStatus.PAUSED));
        var report = reconciliation(3).run("c-1");
        assertThat(pageReads).extracting(read -> read[0]).containsExactly(0, 3, 6);
        assertThat(pageReads).allSatisfy(read -> assertThat(read[1]).isEqualTo(3));
        assertThat(report.status()).isEqualTo(CaptureScopeReconciliation.Status.COMPLETED);
        assertThat(report.attempted()).isEqualTo(7);
        assertThat(report.synced()).isEqualTo(7);
        assertThat(sent).extracting(Scope::pipelineId).containsExactlyElementsOf(pipelines.stream().map(PipelineView::id).toList());
        for (int i = 0; i < 7; i++) assertThat(sent.get(i).status()).isEqualTo(pipelines.get(i).monitoringStatus());
    }

    @Test
    void exactPageMultipleReadsOneEmptyTrailingPage() {
        for (int i = 0; i < 4; i++) pipelines.add(pipeline(i, MonitoringStatus.ACTIVE));
        reconciliation(2).run("c");
        assertThat(pageReads).extracting(read -> read[0]).containsExactly(0, 2, 4);
    }

    @Test
    void repeatedRunsSendTheSameDesiredStateIdempotently() {
        for (int i = 0; i < 3; i++) pipelines.add(pipeline(i, MonitoringStatus.PAUSED));
        var reconciliation = reconciliation(10);
        reconciliation.run("a");
        var first = List.copyOf(sent);
        sent.clear();
        reconciliation.run("b");
        assertThat(sent).isEqualTo(first);
    }

    @Test
    void perPipelineRejectionsAreCountedAndTheRunContinues() {
        for (int i = 0; i < 4; i++) pipelines.add(pipeline(i, MonitoringStatus.ACTIVE));
        var notAdmitted = pipelines.get(1).id();
        var mismatch = pipelines.get(2).id();
        answer = scope -> scope.pipelineId().equals(notAdmitted) ? Outcome.REPOSITORY_NOT_ADMITTED
                : scope.pipelineId().equals(mismatch) ? Outcome.PIPELINE_MISMATCH : Outcome.SYNCED;
        var report = reconciliation(2).run("c");
        assertThat(report.status()).isEqualTo(CaptureScopeReconciliation.Status.COMPLETED_WITH_FAILURES);
        assertThat(report.attempted()).isEqualTo(4);
        assertThat(report.synced()).isEqualTo(2);
        assertThat(report.failures()).isEqualTo(Map.of(Outcome.REPOSITORY_NOT_ADMITTED, 1, Outcome.PIPELINE_MISMATCH, 1));
    }

    @Test
    void unavailableIngestionAbortsThePassAndTheNextRunStartsOver() {
        for (int i = 0; i < 5; i++) pipelines.add(pipeline(i, MonitoringStatus.ACTIVE));
        answer = scope -> Outcome.UNAVAILABLE;
        var reconciliation = reconciliation(2);
        var down = reconciliation.run("c");
        assertThat(down.status()).isEqualTo(CaptureScopeReconciliation.Status.ABORTED_UNAVAILABLE);
        assertThat(sent).hasSize(1);
        assertThat(reconciliation.last()).isEqualTo(down);
        answer = scope -> Outcome.SYNCED;
        sent.clear();
        assertThat(reconciliation.run("c").synced()).isEqualTo(5);
        assertThat(sent).hasSize(5);
    }

    @Test
    void incompleteRowsAndReadFailuresDoNotEscape() {
        pipelines.add(new PipelineView(UUID.randomUUID(), UUID.randomUUID(), null, "x/y", "main", "1", "w", "w.yml",
                null, PipelineCriticality.HIGH, MonitoringStatus.ACTIVE, null, null, CLOCK.instant(), Set.of()));
        pipelines.add(pipeline(1, MonitoringStatus.ACTIVE));
        var report = reconciliation(5).run("c");
        assertThat(report.failures()).isEqualTo(Map.of(Outcome.INVALID_REQUEST, 1));
        assertThat(report.synced()).isEqualTo(1);

        var failing = new CaptureScopeReconciliation((offset, limit) -> { throw new IllegalStateException("db down"); },
                sync, 5, CLOCK);
        assertThat(failing.run("c").status()).isEqualTo(CaptureScopeReconciliation.Status.FAILED);
    }

    @Test
    void oneInstanceNeverOverlapsItsOwnRuns() throws Exception {
        pipelines.add(pipeline(0, MonitoringStatus.ACTIVE));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        answer = scope -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Outcome.SYNCED;
        };
        var reconciliation = reconciliation(5);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> reconciliation.run("first"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reconciliation.run("second").status()).isEqualTo(CaptureScopeReconciliation.Status.SKIPPED_RUNNING);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(CaptureScopeReconciliation.Status.COMPLETED);
        }
        assertThat(sent).hasSize(1);
        assertThat(reconciliation.run("third").status()).isEqualTo(CaptureScopeReconciliation.Status.COMPLETED);
    }

    private static PipelineView pipeline(int index, MonitoringStatus status) {
        return new PipelineView(UUID.randomUUID(), UUID.randomUUID(), String.valueOf(824000 + index), "org/repo", "main",
                String.valueOf(919000 + index), "Build", "build.yml", null, PipelineCriticality.HIGH, status, null, null,
                CLOCK.instant(), Set.of());
    }
}
