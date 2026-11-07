package org.pipesor.pipeline.execution.application.services;

/** Safe permanent rejection reason, suitable for DLQ metadata without the payload. */
public final class SnapshotRejected extends RuntimeException {
    public SnapshotRejected(String reason) { super(reason); }
}
