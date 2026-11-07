package org.pipesor.pipeline.execution.application.commands;

import java.util.Objects;

/** Digests are computed by the trusted inbound transformer, never supplied by the producer. */
public record ApplyExecutionSnapshotCommand(ExecutionSnapshot snapshot, String messageDigest, String contentDigest) {
    public ApplyExecutionSnapshotCommand {
        Objects.requireNonNull(snapshot);
        if (messageDigest == null || !messageDigest.matches("[0-9a-f]{64}")
                || contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 digests required");
        }
    }
}
