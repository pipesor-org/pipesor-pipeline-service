package org.pipesor.pipeline.execution.application.services;

/**
 * Retryable outcome: the snapshot may apply later (for example its workflow is registered after
 * capture). The consumer abandons the message for redelivery instead of dead-lettering it.
 */
public final class SnapshotDeferred extends RuntimeException {
    public SnapshotDeferred(String reason) { super(reason, null, false, false); }
}
