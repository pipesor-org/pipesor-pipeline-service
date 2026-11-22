package org.pipesor.pipeline.configuration;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.interfaces.messaging.ServiceBusSnapshotConsumer;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Receives {@code execution.snapshot} from the {@code workflow-evidence} topic. The connection is
 * selected by configuration: an emulator connection string under the {@code local} profile, or the
 * namespace with {@code DefaultAzureCredential} (managed identity) under {@code azure}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pipeline.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties({ServiceBusProperties.class, MessagingProperties.class})
public class ServiceBusConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConfiguration.class);

    @Bean
    ReceiverLifecycle executionReceiver(ExecutionSnapshotService service, JdbcTemplate jdbc, ServiceBusProperties bus,
            MessagingProperties limits, TransportPolicy transport) {
        jdbc.queryForList("SELECT event_id, message_digest, content_digest, source_updated_at, source_submicro_nanos, disposition FROM ops.execution_event_receipts LIMIT 0");
        var consumer = new ServiceBusSnapshotConsumer(service, limits.maxMessageBytes(), limits.maxDeliveries());
        var builder = new ServiceBusClientBuilder();
        if (bus.usesConnectionString()) {
            if (!transport.loopbackHttpAllowed()) {
                throw new IllegalStateException("A Service Bus connection string is allowed only under the local profile");
            }
            builder.connectionString(bus.connectionString());
        } else {
            builder.credential(bus.namespace(), new DefaultAzureCredentialBuilder().build());
        }
        var processor = builder.processor().topicName(bus.topic()).subscriptionName(bus.subscription())
                .disableAutoComplete().prefetchCount(0).maxConcurrentCalls(limits.concurrency())
                .maxAutoLockRenewDuration(limits.lockRenewal()).drainTimeout(limits.drainTimeout())
                .processMessage(consumer::accept)
                .processError(ServiceBusConfiguration::logError)
                .buildProcessorClient();
        return new ReceiverLifecycle(processor);
    }

    @Bean
    HealthIndicator serviceBus(ReceiverLifecycle receiver) {
        return () -> receiver.isRunning() && receiver.client.isRunning() ? Health.up().build() : Health.down().build();
    }

    private static void logError(ServiceBusErrorContext error) {
        LOG.warn("Service Bus receive failure: source={}, entity={}, type={}", error.getErrorSource(), error.getEntityPath(),
                error.getException().getClass().getName(), error.getException());
    }

    static final class ReceiverLifecycle implements SmartLifecycle {
        private final ServiceBusProcessorClient client;
        private volatile boolean running;
        ReceiverLifecycle(ServiceBusProcessorClient client) { this.client = client; }
        @Override public void start() { client.start(); running = true; }
        @Override public void stop() { running = false; client.close(); }
        @Override public boolean isRunning() { return running; }
    }
}
