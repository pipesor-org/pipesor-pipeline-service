package org.pipesor.pipeline.catalog.application.services;

public final class GitHubVerificationUnavailable extends RuntimeException {
    public GitHubVerificationUnavailable() { super("Current GitHub verification is unavailable"); }
}
