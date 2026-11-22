package org.pipesor.pipeline.execution.interfaces.messaging;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import java.util.Objects;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;

/**
 * The single place where a Service Bus envelope is checked against its snapshot: application
 * properties {@code eventType} and {@code schemaMajor}, {@code messageId == eventId} and the
 * broker correlation id equal to the payload's.
 */
public final class SnapshotEnvelope {
    public static final String EVENT_TYPE = "execution.snapshot";
    public static final int SCHEMA_MAJOR = 1;

    private SnapshotEnvelope() {}

    /** Returns null when valid, otherwise a safe dead-letter reason. */
    public static String violation(ServiceBusReceivedMessage message, ExecutionSnapshot snapshot) {
        var properties = message.getApplicationProperties();
        if (properties == null || !EVENT_TYPE.equals(properties.get("eventType"))
                || !EVENT_TYPE.equals(snapshot.eventType())) {
            return "UNSUPPORTED_EVENT_TYPE";
        }
        if (!major(properties.get("schemaMajor")) || !snapshot.schemaVersion().startsWith(SCHEMA_MAJOR + ".")) {
            return "UNSUPPORTED_SCHEMA_MAJOR";
        }
        if (!Objects.equals(message.getMessageId(), snapshot.eventId().toString())) {
            return "MESSAGE_ID_MISMATCH";
        }
        if (!Objects.equals(message.getCorrelationId(), snapshot.correlationId())) {
            return "CORRELATION_ID_MISMATCH";
        }
        return null;
    }

    private static boolean major(Object value) {
        return switch (value) {
            case Integer number -> number == SCHEMA_MAJOR;
            case Long number -> number == SCHEMA_MAJOR;
            case Short number -> number == SCHEMA_MAJOR;
            case Byte number -> number == SCHEMA_MAJOR;
            case String text -> text.equals(Integer.toString(SCHEMA_MAJOR));
            case null, default -> false;
        };
    }
}
