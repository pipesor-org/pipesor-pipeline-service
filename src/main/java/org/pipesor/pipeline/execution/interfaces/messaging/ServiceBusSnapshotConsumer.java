package org.pipesor.pipeline.execution.interfaces.messaging;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.application.services.SnapshotDeferred;
import org.pipesor.pipeline.execution.application.services.SnapshotRejected;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * Settles each delivery after its transaction: complete when applied or duplicate, dead-letter
 * permanent rejections with a safe reason, abandon retryable outcomes for redelivery. Only after the
 * subscription's delivery budget is spent does a retryable outcome become {@code RETRY_EXHAUSTED}.
 */
public final class ServiceBusSnapshotConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusSnapshotConsumer.class);
    private final ExecutionSnapshotService service;
    private final ExecutionSnapshotTransformer transform = new ExecutionSnapshotTransformer();
    private final int maxMessageBytes;
    private final int maxDeliveries;

    public ServiceBusSnapshotConsumer(ExecutionSnapshotService service, int maxMessageBytes, int maxDeliveries) {
        if (maxMessageBytes < 1 || maxDeliveries < 1) { throw new IllegalArgumentException("Positive message limits required"); }
        this.service = service;
        this.maxMessageBytes = maxMessageBytes;
        this.maxDeliveries = maxDeliveries;
    }

    public void accept(ServiceBusReceivedMessageContext context) {
        var message = context.getMessage();
        ApplyExecutionSnapshotCommand command;
        try {
            var payload = message.getBody().toBytes();
            if (payload.length > maxMessageBytes) {
                reject(context, "PAYLOAD_TOO_LARGE");
                return;
            }
            command = transform.fromJson(payload);
        } catch (JacksonException | IllegalArgumentException | NullPointerException malformed) {
            reject(context, "MALFORMED_OR_UNSUPPORTED_SNAPSHOT");
            return;
        }
        String violation = SnapshotEnvelope.violation(message, command.snapshot());
        if (violation != null) {
            reject(context, violation);
            return;
        }
        String correlation = command.snapshot().correlationId();
        try {
            service.handle(command);
        } catch (SnapshotRejected permanent) {
            reject(context, permanent.getMessage());
            return;
        } catch (SnapshotDeferred deferred) {
            retry(context, deferred.getMessage(), correlation, null);
            return;
        } catch (RuntimeException retryable) {
            retry(context, "PROCESSING_FAILED", correlation, retryable);
            return;
        }
        // Settlement is outside the transaction: a lost acknowledgement is safely redelivered.
        context.complete();
    }

    private void retry(ServiceBusReceivedMessageContext context, String reason, String correlation, RuntimeException failure) {
        long delivery = context.getMessage().getDeliveryCount();
        if (failure != null) {
            LOG.warn("Snapshot processing failed: correlation={}, delivery={}, type={}", correlation, delivery,
                    failure.getClass().getName(), failure);
        } else {
            LOG.info("Snapshot deferred for redelivery: correlation={}, delivery={}, reason={}", correlation, delivery, reason);
        }
        if (delivery >= maxDeliveries) { reject(context, "RETRY_EXHAUSTED"); }
        else { context.abandon(); }
    }

    private void reject(ServiceBusReceivedMessageContext context, String reason) {
        LOG.warn("Snapshot dead-lettered: messageId={}, reason={}", context.getMessage().getMessageId(), reason);
        context.deadLetter(new DeadLetterOptions().setDeadLetterReason(reason));
    }
}
