package org.pipesor.pipeline.execution.application.commands;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;

public record ExecutionSnapshot(String schemaVersion, UUID eventId, String eventType, String source,
        Instant occurredAt, Instant sourceUpdatedAt, String correlationId, Pipeline pipeline,
        Execution execution, Commit commit, String service, String environment,
        List<Stage> stages, Failure failure) {
    public ExecutionSnapshot {
        if (schemaVersion == null || !schemaVersion.matches("1\\.[0-9]+")
                || !"execution.snapshot".equals(eventType) || !"github_actions".equals(source)) {
            throw new IllegalArgumentException("Unsupported snapshot type, source or version");
        }
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt is required");
        Objects.requireNonNull(pipeline, "pipeline is required");
        Objects.requireNonNull(execution, "execution is required");
        Objects.requireNonNull(commit, "commit is required");
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Safe correlationId required");
        }
        DeterministicIds.pipeline(pipeline.githubRepositoryId(), pipeline.githubWorkflowId());
        DeterministicIds.execution(pipeline.githubRepositoryId(), execution.githubRunId(), execution.runAttempt());
        boolean failed = "completed".equals(execution.status())
                && ("failure".equals(execution.conclusion()) || "timed_out".equals(execution.conclusion()));
        if (failed != (failure != null)) { throw new IllegalArgumentException("Failure context must match the terminal result"); }
        var seen = new HashSet<PipelineStage>();
        stages = List.copyOf(Objects.requireNonNull(stages, "stages is required"));
        for (var stage : stages) {
            if (!seen.add(stage.stage())) { throw new IllegalArgumentException("Duplicate normalized stage"); }
        }
        stages = stages.stream().sorted(Comparator.comparing(Stage::stage)).toList();
    }

    public boolean failed() { return failure != null; }
    public UUID executionId() {
        return DeterministicIds.execution(pipeline.githubRepositoryId(), execution.githubRunId(), execution.runAttempt());
    }

    public record Pipeline(String githubRepositoryId, String githubWorkflowId) {}
    public record Commit(String sha) {
        public Commit {
            if (sha == null || !sha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
                throw new IllegalArgumentException("Full commit SHA required");
            }
            sha = sha.toLowerCase(Locale.ROOT);
        }
    }
    public record Execution(String githubRunId, int runAttempt, String branch, String status,
            String conclusion, Instant startedAt, Instant completedAt) {
        public Execution {
            if (branch == null || branch.isBlank() || status == null || status.isBlank()
                    || ("completed".equals(status) && (conclusion == null || conclusion.isBlank()))
                    || (!"completed".equals(status) && (conclusion != null || completedAt != null))
                    || (startedAt != null && completedAt != null && completedAt.isBefore(startedAt))) {
                throw new IllegalArgumentException("Inconsistent execution snapshot");
            }
        }
    }
    public record Stage(PipelineStage stage, String status, Instant startedAt, Instant completedAt) {
        public Stage {
            Objects.requireNonNull(stage, "stage is required");
            if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("Inconsistent stage timestamps");
            }
        }
    }
    public record Failure(PipelineStage stage, String summary, Instant detectedAt) {
        public Failure {
            Objects.requireNonNull(stage, "failure stage is required");
            Objects.requireNonNull(detectedAt, "detectedAt is required");
            if (summary == null || summary.isBlank()) { throw new IllegalArgumentException("Sanitized failure summary required"); }
        }
    }
}
