package org.pipesor.pipeline.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Delivery limits of the snapshot receiver. {@code maxDeliveries} matches the subscription's max delivery count. */
@ConfigurationProperties("pipeline.messaging")
public record MessagingProperties(boolean enabled, int maxMessageBytes, int concurrency, Duration lockRenewal,
        Duration drainTimeout, int maxDeliveries) {
    public MessagingProperties {
        maxMessageBytes = maxMessageBytes == 0 ? 262144 : maxMessageBytes;
        concurrency = concurrency == 0 ? 4 : concurrency;
        lockRenewal = lockRenewal == null ? Duration.ofMinutes(5) : lockRenewal;
        drainTimeout = drainTimeout == null ? Duration.ofSeconds(30) : drainTimeout;
        maxDeliveries = maxDeliveries == 0 ? 10 : maxDeliveries;
        if (maxMessageBytes < 1 || concurrency < 1 || lockRenewal.isNegative() || drainTimeout.isNegative()
                || drainTimeout.isZero() || maxDeliveries < 1) {
            throw new IllegalArgumentException("Valid Service Bus delivery limits are required");
        }
    }
}
