package org.pipesor.pipeline.catalog.application.queries;

/** ADR-028: which workflows of one GitHub repository are monitored, for someone who can read it. */
public record ListRepositoryRegistrationsQuery(String actorRef, String githubRepositoryId) {
    public ListRepositoryRegistrationsQuery {
        if (actorRef == null || actorRef.isBlank()) { throw new IllegalArgumentException("Actor required"); }
        if (githubRepositoryId == null || !githubRepositoryId.matches("[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException("Numeric GitHub repository id required");
        }
    }
}
