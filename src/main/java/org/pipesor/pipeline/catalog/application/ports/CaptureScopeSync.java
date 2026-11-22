package org.pipesor.pipeline.catalog.application.ports;

import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;

/**
 * Drives Ingestion's capture workflow scope from a committed registration (ADR-023). Calls happen
 * outside any local transaction and are idempotent per status; implementations never throw for
 * remote failures and report them as an {@link Outcome} instead.
 */
public interface CaptureScopeSync {
    Result sync(Scope scope, String correlationId);

    /** Desired scope state: the GitHub workflow bound to one pipeline with its monitoring status. */
    record Scope(String githubRepositoryId, String githubWorkflowId, UUID pipelineId, MonitoringStatus status) {
        public Scope {
            Objects.requireNonNull(githubRepositoryId, "githubRepositoryId is required");
            Objects.requireNonNull(githubWorkflowId, "githubWorkflowId is required");
            Objects.requireNonNull(pipelineId, "pipelineId is required");
            Objects.requireNonNull(status, "status is required");
        }

        public static Scope of(PipelineView pipeline) {
            return new Scope(pipeline.githubRepositoryId(), pipeline.githubWorkflowId(), pipeline.id(),
                    pipeline.monitoringStatus());
        }
    }

    enum Outcome {
        /** Ingestion stores the requested status for this pipeline. */
        SYNCED,
        /** The repository has no operator admission for the tenant (404). */
        REPOSITORY_NOT_ADMITTED,
        /** The workflow scope is bound to another pipeline id (409). */
        PIPELINE_MISMATCH,
        /** Ingestion rejected the identifiers or body (400), or they were not sent because they are invalid. */
        INVALID_REQUEST,
        /** The workload token was refused (401/403). */
        DENIED,
        /** Ingestion is unreachable, timed out or reported itself unavailable (503/5xx). */
        UNAVAILABLE,
        /** Any other status or a response that does not echo the request. */
        RESPONSE_INVALID
    }

    /** {@code scopeRevision} and {@code revision} are present only when {@link Outcome#SYNCED}. */
    record Result(Outcome outcome, String scopeRevision, Long revision) {
        public Result { Objects.requireNonNull(outcome); }

        public static Result failed(Outcome outcome) {
            if (outcome == Outcome.SYNCED) throw new IllegalArgumentException("A synced result carries its revision");
            return new Result(outcome, null, null);
        }

        public boolean synced() { return outcome == Outcome.SYNCED; }
    }
}
