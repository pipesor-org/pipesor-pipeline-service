package org.pipesor.pipeline.configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.CaptureScopeReconciliation;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureScopeConfigurationTests {
    private static final PipelineView PIPELINE = new PipelineView(UUID.randomUUID(), UUID.randomUUID(), "824001", "o/r",
            "main", "919001", "Build", "build.yml", null, PipelineCriticality.HIGH, MonitoringStatus.ACTIVE, null, null,
            Clock.systemUTC().instant(), Set.of());

    @Test
    void reconcilerRunsAtReadinessAndStopsOnShutdown() throws Exception {
        var ran = new CountDownLatch(1);
        CaptureScopeSync sync = (scope, correlation) -> {
            ran.countDown();
            return CaptureScopeSync.Result.failed(CaptureScopeSync.Outcome.UNAVAILABLE);
        };
        var reconciliation = new CaptureScopeReconciliation((offset, limit) -> offset == 0 ? List.of(PIPELINE) : List.of(),
                sync, 10, Clock.systemUTC());
        var reconciler = new CaptureScopeConfiguration.CaptureScopeReconciler(reconciliation, Duration.ofMinutes(5));
        try {
            reconciler.onApplicationEvent(null);
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            reconciler.destroy();
        }
        // Readiness stays UP with the failed pass only as a detail.
        var properties = new CaptureScopeProperties(null, null, 0);
        var health = new CaptureScopeConfiguration().captureScope(reconciliation, properties).health(true);
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        for (int i = 0; i < 50 && reconciliation.last() == null; i++) Thread.sleep(20);
        health = new CaptureScopeConfiguration().captureScope(reconciliation, properties).health(true);
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastRun", "ABORTED_UNAVAILABLE");
    }

    @Test
    void propertiesDefaultAndValidate() {
        var defaults = new CaptureScopeProperties(null, null, 0);
        assertThat(defaults.reconcileEnabled()).isTrue();
        assertThat(defaults.reconcileInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.pageSize()).isEqualTo(100);
        assertThat(new CaptureScopeProperties(false, Duration.ofMinutes(1), 10).reconcileEnabled()).isFalse();
        assertThatThrownBy(() -> new CaptureScopeProperties(true, Duration.ofSeconds(1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaptureScopeProperties(true, null, 5000)).isInstanceOf(IllegalArgumentException.class);
    }
}
