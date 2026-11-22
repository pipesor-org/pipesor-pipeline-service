package org.pipesor.pipeline.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reconciliation of Ingestion capture workflow scopes (ADR-023): enabled by default, every
 * {@code reconcileInterval} (5 min) after readiness, reading {@code pageSize} pipelines per page.
 */
@ConfigurationProperties("pipeline.capture-scope")
public record CaptureScopeProperties(Boolean reconcileEnabled, Duration reconcileInterval, int pageSize) {
    public CaptureScopeProperties {
        reconcileEnabled = reconcileEnabled == null || reconcileEnabled;
        reconcileInterval = reconcileInterval == null ? Duration.ofMinutes(5) : reconcileInterval;
        pageSize = pageSize == 0 ? 100 : pageSize;
        if (reconcileInterval.compareTo(Duration.ofSeconds(10)) < 0 || pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("A reconcile interval of at least 10 s and a page size of 1..1000 are required");
        }
    }
}
