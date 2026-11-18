package org.pipesor.pipeline.execution.application.queryhandlers;

import java.util.Objects;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceAuthority;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceAuthority.Permission;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup;
import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;

public final class ValidateOperationalReferences {
    public enum Decision { AUTHORIZED, DENIED, NOT_READY, UNAVAILABLE, MISMATCH }
    private final OperationalReferenceAuthority authority;
    private final OperationalReferenceLookup references;

    public ValidateOperationalReferences(OperationalReferenceAuthority authority, OperationalReferenceLookup references) {
        this.authority = Objects.requireNonNull(authority);
        this.references = Objects.requireNonNull(references);
    }

    public Decision handle(OperationalReferenceQuery query) {
        Objects.requireNonNull(query);
        try {
            Permission permission = authority.verify(query);
            if (permission != Permission.AUTHORIZED) {
                return permission == Permission.DENIED ? Decision.DENIED : Decision.UNAVAILABLE;
            }
            if (!query.pipelineId().equals(DeterministicIds.pipeline(query.githubRepositoryId(), query.githubWorkflowId()))
                    || !query.executionId().equals(DeterministicIds.execution(query.githubRepositoryId(), query.githubRunId(), query.runAttempt()))
                    || !query.incidentId().equals(DeterministicIds.incident(query.executionId()))) {
                return Decision.MISMATCH;
            }
            var result = references.check(query);
            if (result == null) return Decision.UNAVAILABLE;
            return switch (result) {
                case MATCH -> Decision.AUTHORIZED;
                case NOT_READY -> Decision.NOT_READY;
                case MISMATCH -> Decision.MISMATCH;
            };
        } catch (RuntimeException unavailable) {
            return Decision.UNAVAILABLE;
        }
    }
}
