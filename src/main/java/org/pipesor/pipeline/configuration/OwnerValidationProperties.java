package org.pipesor.pipeline.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits of {@code POST /internal/v1/evidence/operational-reference-checks}; callers come from the route catalog. */
@ConfigurationProperties("pipeline.owner-validation")
public record OwnerValidationProperties(Duration requestTimeout, int maxRequestBytes, int maxResponseBytes, int maxInFlight) {
    public OwnerValidationProperties {
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        maxRequestBytes = maxRequestBytes == 0 ? 4096 : maxRequestBytes;
        maxResponseBytes = maxResponseBytes == 0 ? 1024 : maxResponseBytes;
        maxInFlight = maxInFlight == 0 ? 16 : maxInFlight;
        if (requestTimeout.isNegative() || requestTimeout.isZero() || maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE
                || maxResponseBytes < 1 || maxResponseBytes == Integer.MAX_VALUE || maxInFlight < 1) {
            throw new IllegalArgumentException("Valid owner validation limits are required");
        }
        requestTimeout.toNanos();
    }
}
