package org.pipesor.pipeline.configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.CaptureScopeReconciliation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Capture workflow scopes in Ingestion follow registrations (ADR-023): a sync after each committed
 * registration or monitoring-status change, plus a reconciliation pass at readiness and periodically.
 * Readiness reports the last pass only as a detail; Ingestion being unreachable never makes it DOWN.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CaptureScopeProperties.class)
public class CaptureScopeConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(CaptureScopeConfiguration.class);

    @Bean
    CaptureScopeReconciliation captureScopeReconciliation(PipelineLookup pipelines, CaptureScopeSync sync,
            CaptureScopeProperties settings, Clock clock) {
        return new CaptureScopeReconciliation((offset, limit) -> pipelines.list(null, offset, limit), sync,
                settings.pageSize(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "pipeline.capture-scope.reconcile-enabled", havingValue = "true", matchIfMissing = true)
    CaptureScopeReconciler captureScopeReconciler(CaptureScopeReconciliation reconciliation, CaptureScopeProperties settings) {
        return new CaptureScopeReconciler(reconciliation, settings.reconcileInterval());
    }

    @Bean
    HealthIndicator captureScope(CaptureScopeReconciliation reconciliation, CaptureScopeProperties settings) {
        return () -> {
            var health = Health.up().withDetail("reconcileEnabled", settings.reconcileEnabled());
            var last = reconciliation.last();
            if (last == null) return health.withDetail("lastRun", "PENDING").build();
            return health.withDetail("lastRun", last.status().name()).withDetail("lastRunFinishedAt", last.finishedAt().toString())
                    .withDetail("attempted", last.attempted()).withDetail("synced", last.synced()).build();
        };
    }

    /** Post-commit sync: never fails the already committed request; reconciliation repairs any miss. */
    static void afterCommit(CaptureScopeSync sync, PipelineView pipeline, String correlationId) {
        try {
            var result = sync.sync(CaptureScopeSync.Scope.of(pipeline), correlationId);
            if (!result.synced()) {
                LOG.warn("Capture scope sync deferred to reconciliation: correlation={}, pipeline={}, outcome={}",
                        correlationId, pipeline.id(), result.outcome());
            }
        } catch (RuntimeException failure) {
            LOG.warn("Capture scope sync deferred to reconciliation: correlation={}, pipeline={}, reason=unexpected, type={}",
                    correlationId, pipeline.id(), failure.getClass().getSimpleName());
        }
    }

    /** Single-threaded fixed-delay schedule started at readiness; runs of one instance never overlap. */
    static final class CaptureScopeReconciler implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {
        private final CaptureScopeReconciliation reconciliation;
        private final Duration interval;
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "capture-scope-reconciler");
            thread.setDaemon(true);
            return thread;
        });

        CaptureScopeReconciler(CaptureScopeReconciliation reconciliation, Duration interval) {
            this.reconciliation = reconciliation;
            this.interval = interval;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            if (executor.isShutdown()) return;
            executor.scheduleWithFixedDelay(this::runOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }

        void runOnce() {
            String correlation = UUID.randomUUID().toString();
            try {
                var report = reconciliation.run(correlation);
                if (report.status() == CaptureScopeReconciliation.Status.COMPLETED) {
                    LOG.info("Capture scope reconciliation completed: correlation={}, attempted={}, synced={}",
                            correlation, report.attempted(), report.synced());
                } else {
                    LOG.warn("Capture scope reconciliation {}: correlation={}, attempted={}, synced={}, failures={}",
                            report.status(), correlation, report.attempted(), report.synced(), report.failures());
                }
            } catch (RuntimeException unexpected) {
                LOG.warn("Capture scope reconciliation failed: correlation={}, type={}", correlation,
                        unexpected.getClass().getSimpleName());
            }
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }
    }
}
