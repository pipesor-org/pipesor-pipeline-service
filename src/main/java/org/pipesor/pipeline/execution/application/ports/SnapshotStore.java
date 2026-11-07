package org.pipesor.pipeline.execution.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;

public interface SnapshotStore {
    void lockEvent(UUID eventId);
    Optional<String> receiptDigest(UUID eventId);
    Optional<Registration> lockPipeline(String repositoryId, String workflowId);
    Optional<Version> latest(UUID executionId);
    Optional<StoredExecution> current(UUID executionId, UUID pipelineId, String runId, int attempt);
    boolean incidentExists(UUID id);
    void persist(ExecutionSnapshot snapshot, UUID pipelineId, Instant now);
    void receipt(ApplyExecutionSnapshotCommand command, String disposition, Instant now);

    record Registration(UUID id, boolean active) {}
    record Version(Instant sourceUpdatedAt, String contentDigest) {}
    record StoredExecution(UUID id, UUID pipelineId, String runId, int attempt, String status, String conclusion) {}
}
