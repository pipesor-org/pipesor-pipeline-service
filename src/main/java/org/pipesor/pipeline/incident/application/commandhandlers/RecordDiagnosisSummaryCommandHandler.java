package org.pipesor.pipeline.incident.application.commandhandlers;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.pipesor.pipeline.incident.application.commands.DiagnosisSummaryScopeMismatch;
import org.pipesor.pipeline.incident.application.commands.RecordDiagnosisSummaryCommand;
import org.pipesor.pipeline.incident.application.ports.DiagnosisSummaryStore;
import org.pipesor.pipeline.incident.application.services.DiagnosisSummaryService;

/**
 * Applies Recommendation's diagnosis summary to the projection (ADR-027) only when it binds to the incident: the
 * execution is the incident's ORIGIN execution and the scope's pipeline and repository are the incident's, the same
 * binding the Workspace requires. Writes are monotonic on (version, reviewRevision), so replays and late deliveries
 * never move the projection backwards.
 */
public final class RecordDiagnosisSummaryCommandHandler implements DiagnosisSummaryService {
    private final DiagnosisSummaryStore store;
    private final Clock clock;

    public RecordDiagnosisSummaryCommandHandler(DiagnosisSummaryStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RecordDiagnosisSummaryCommand.Result handle(RecordDiagnosisSummaryCommand command) {
        var target = store.target(command.incidentId()).orElseThrow(NoSuchElementException::new);
        if (!command.executionId().equals(target.originExecutionId()) || !command.pipelineId().equals(target.pipelineId())
                || !command.githubRepositoryId().equals(target.githubRepositoryId())) {
            throw new DiagnosisSummaryScopeMismatch();
        }
        boolean applied = store.apply(command, clock.instant());
        return new RecordDiagnosisSummaryCommand.Result(command.incidentId(), command.diagnosisId(), command.version(),
                command.reviewRevision(), command.status(), applied);
    }
}
