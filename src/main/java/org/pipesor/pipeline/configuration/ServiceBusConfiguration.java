package org.pipesor.pipeline.configuration;

import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import java.time.Duration;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.interfaces.messaging.ServiceBusSnapshotConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pipeline.messaging.enabled", havingValue = "true")
public class ServiceBusConfiguration {
    @Bean
    SmartLifecycle executionReceiver(ExecutionSnapshotService service, JdbcTemplate jdbc,
            @Value("${pipeline.messaging.namespace}") String namespace,
            @Value("${pipeline.messaging.topic}") String topic,
            @Value("${pipeline.messaging.subscription}") String subscription,
            @Value("${pipeline.messaging.max-message-bytes}") int maxMessageBytes,
            @Value("${pipeline.messaging.concurrency}") int concurrency,
            @Value("${pipeline.messaging.lock-renewal-seconds}") long lockRenewalSeconds,
            @Value("${pipeline.messaging.drain-seconds}") long drainSeconds) {
        if (namespace.isBlank() || topic.isBlank() || subscription.isBlank() || concurrency < 1 || lockRenewalSeconds < 0 || drainSeconds < 1) {
            throw new IllegalArgumentException("Complete namespace/topic/subscription and valid delivery limits required");
        }
        jdbc.queryForList("SELECT event_id, message_digest, content_digest, source_updated_at, source_submicro_nanos, disposition FROM ops.execution_event_receipts LIMIT 0");
        var consumer = new ServiceBusSnapshotConsumer(service, maxMessageBytes);
        var processor = new ServiceBusClientBuilder()
                .credential(namespace, new ManagedIdentityCredentialBuilder().build())
                .processor().topicName(topic).subscriptionName(subscription)
                .disableAutoComplete().prefetchCount(0).maxConcurrentCalls(concurrency)
                .maxAutoLockRenewDuration(Duration.ofSeconds(lockRenewalSeconds))
                .drainTimeout(Duration.ofSeconds(drainSeconds))
                .processMessage(consumer::accept)
                .processError(error -> System.getLogger(ServiceBusConfiguration.class.getName())
                        .log(System.Logger.Level.WARNING, "Service Bus receive failure: {0}", error.getErrorSource()))
                .buildProcessorClient();
        return new ReceiverLifecycle(processor);
    }

    private static final class ReceiverLifecycle implements SmartLifecycle {
        private final ServiceBusProcessorClient client;
        private volatile boolean running;
        ReceiverLifecycle(ServiceBusProcessorClient client) { this.client = client; }
        @Override public void start() { client.start(); running = true; }
        @Override public void stop() { client.close(); running = false; }
        @Override public boolean isRunning() { return running; }
    }
}
