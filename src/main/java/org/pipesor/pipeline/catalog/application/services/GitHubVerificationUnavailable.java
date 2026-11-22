package org.pipesor.pipeline.catalog.application.services;

/** Current GitHub facts could not be used; the reason is a fixed code safe to log. */
public final class GitHubVerificationUnavailable extends RuntimeException {
    public enum Reason {
        UNSPECIFIED, NO_VERIFIED_SESSION, SESSION_ACTOR_MISMATCH, DEADLINE_EXCEEDED, CONNECTOR_UNREACHABLE,
        CONNECTOR_REJECTED, RESPONSE_INVALID, FACTS_BINDING_MISMATCH, FACTS_EXPIRED, GITHUB_USER_INVALID,
        REPOSITORY_FACT_INVALID, WORKFLOW_FACTS_INCOMPLETE
    }

    private final Reason reason;

    public GitHubVerificationUnavailable() { this(Reason.UNSPECIFIED); }

    public GitHubVerificationUnavailable(Reason reason) {
        super("Current GitHub verification is unavailable");
        this.reason = reason == null ? Reason.UNSPECIFIED : reason;
    }

    public Reason reason() { return reason; }
}
