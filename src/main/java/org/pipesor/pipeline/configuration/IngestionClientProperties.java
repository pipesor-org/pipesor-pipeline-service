package org.pipesor.pipeline.configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound Ingestion client: base URL from {@code PIPESOR_ENDPOINTS_INGESTION} plus explicit
 * timeouts and size limits for the GitHub facts routes.
 */
@ConfigurationProperties("pipeline.ingestion")
public record IngestionClientProperties(URI baseUri, Duration connectTimeout, Duration requestTimeout,
        Duration decisionBudget, int maxResponseBytes, int maxBatchSize) {
    public IngestionClientProperties {
        Objects.requireNonNull(baseUri, "PIPESOR_ENDPOINTS_INGESTION is required");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        decisionBudget = decisionBudget == null ? Duration.ofSeconds(5) : decisionBudget;
        maxResponseBytes = maxResponseBytes == 0 ? 262144 : maxResponseBytes;
        maxBatchSize = maxBatchSize == 0 ? 50 : maxBatchSize;
        if (!positive(connectTimeout) || !positive(requestTimeout) || decisionBudget.isNegative()
                || requestTimeout.plus(decisionBudget).compareTo(Duration.ofSeconds(15)) > 0
                || maxResponseBytes < 1 || maxBatchSize < 1) {
            throw new IllegalArgumentException("Valid Ingestion client limits are required");
        }
    }

    private static boolean positive(Duration value) { return !value.isNegative() && !value.isZero(); }
}
