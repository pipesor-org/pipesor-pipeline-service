package org.pipesor.pipeline.execution.application.queries;

import java.time.Instant;
import java.util.UUID;

public record ExecutionView(
        UUID id,
        UUID pipelineId,
        String githubRunId,
        int runAttempt,
        String branch,
        String commitSha,
        String externalStatus,
        String externalConclusion,
        boolean failed,
        Instant startedAt,
        Instant completedAt,
        String correlationId,
        Instant createdAt) {
}
