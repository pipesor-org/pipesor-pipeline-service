package org.pipesor.pipeline.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code PIPESOR_SERVICE_BUS_CONNECTION_STRING} (local emulator) or {@code PIPESOR_SERVICE_BUS_NAMESPACE}
 * (azure), plus {@code PIPESOR_SERVICE_BUS_TOPIC} and {@code PIPESOR_SERVICE_BUS_SUBSCRIPTION}.
 */
@ConfigurationProperties("pipesor.service-bus")
public record ServiceBusProperties(String connectionString, String namespace, String topic, String subscription) {
    public ServiceBusProperties {
        if (blank(connectionString) == blank(namespace)) {
            throw new IllegalArgumentException("Exactly one Service Bus connection string or namespace is required");
        }
        if (blank(topic) || blank(subscription)) {
            throw new IllegalArgumentException("Service Bus topic and subscription are required");
        }
    }

    public boolean usesConnectionString() { return !blank(connectionString); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    @Override public String toString() { return "ServiceBusProperties[" + topic + "/" + subscription + "]"; }
}
