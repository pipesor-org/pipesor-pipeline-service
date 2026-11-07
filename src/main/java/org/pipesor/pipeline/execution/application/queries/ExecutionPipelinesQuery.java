package org.pipesor.pipeline.execution.application.queries;

import java.util.Set;
import java.util.UUID;

/** Resolve the ownership of one bounded incident-run page without fetching execution details. */
public record ExecutionPipelinesQuery(Set<UUID> executionIds) {
    public ExecutionPipelinesQuery {
        executionIds = Set.copyOf(executionIds);
        if (executionIds.size() > 100) { throw new IllegalArgumentException("At most 100 execution references per page"); }
    }
}
