package org.pipesor.pipeline.incident.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.pipeline.incident.application.commands.RecordDiagnosisSummaryCommand;

/** Pipeline's projection of each incident's diagnosis summary (ADR-027). */
public interface DiagnosisSummaryStore {
    /** The incident's owning pipeline, its GitHub repository and its ORIGIN execution (null when absent). */
    Optional<Target> target(UUID incidentId);

    /**
     * Stores the summary unless the stored one is already at the same or a later {@code (version, reviewRevision)}.
     * Returns whether it was written.
     */
    boolean apply(RecordDiagnosisSummaryCommand command, Instant projectedAt);

    record Target(UUID incidentId, UUID pipelineId, String githubRepositoryId, UUID originExecutionId) {}
}
