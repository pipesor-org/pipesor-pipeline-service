package org.pipesor.pipeline.execution.interfaces.messaging;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.application.services.SnapshotRejected;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import tools.jackson.core.JacksonException;

public final class ServiceBusSnapshotConsumer {
    private final ExecutionSnapshotService service;
    private final ExecutionSnapshotTransformer transform = new ExecutionSnapshotTransformer();
    private final int maxMessageBytes;

    public ServiceBusSnapshotConsumer(ExecutionSnapshotService service, int maxMessageBytes) {
        if (maxMessageBytes < 1) { throw new IllegalArgumentException("Positive message size limit required"); }
        this.service = service;
        this.maxMessageBytes = maxMessageBytes;
    }

    public void accept(ServiceBusReceivedMessageContext context) {
        ApplyExecutionSnapshotCommand command;
        try {
            var payload = context.getMessage().getBody().toBytes();
            if (payload.length > maxMessageBytes) {
                reject(context, "PAYLOAD_TOO_LARGE");
                return;
            }
            command = transform.fromJson(payload);
        } catch (JacksonException | IllegalArgumentException | NullPointerException malformed) {
            reject(context, "MALFORMED_OR_UNSUPPORTED_SNAPSHOT");
            return;
        }
        try {
            service.handle(command);
        } catch (SnapshotRejected permanent) {
            reject(context, permanent.getMessage());
            return;
        } catch (RuntimeException retryable) {
            if (context.getMessage().getDeliveryCount() >= 10) { reject(context, "RETRY_EXHAUSTED"); }
            else { context.abandon(); }
            return;
        }
        // Settlement is outside the transaction: a lost acknowledgement is safely redelivered.
        context.complete();
    }

    private void reject(ServiceBusReceivedMessageContext context, String reason) {
        context.deadLetter(new DeadLetterOptions().setDeadLetterReason(reason));
    }
}
