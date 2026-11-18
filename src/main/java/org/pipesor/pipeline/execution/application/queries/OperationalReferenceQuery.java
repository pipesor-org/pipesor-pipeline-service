package org.pipesor.pipeline.execution.application.queries;

import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;

/** Internal machine request; the injected authority must bind it to its authenticated caller. */
public record OperationalReferenceQuery(
        UUID tenantId, String githubAccountId, String githubInstallationId,
        String githubRepositoryId, String githubWorkflowId, String scopeRevision,
        String githubRunId, int runAttempt, UUID pipelineId, UUID executionId,
        UUID incidentId, String correlationId) {
    public OperationalReferenceQuery {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(pipelineId);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(incidentId);
        if (tenantId.equals(new UUID(0, 0))) throw new IllegalArgumentException("Invalid capture binding");
        requireExternal(githubAccountId);
        requireExternal(githubInstallationId);
        DeterministicIds.pipeline(githubRepositoryId, githubWorkflowId);
        DeterministicIds.execution(githubRepositoryId, githubRunId, runAttempt);
        requireSafeId(scopeRevision);
        requireSafeId(correlationId);
    }

    private static void requireExternal(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid capture binding");
        }
    }

    private static void requireSafeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Invalid capture binding");
        }
    }

    @Override
    public String toString() { return "OperationalReferenceQuery[protected]"; }
}
