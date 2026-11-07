package org.pipesor.pipeline.catalog.application.services;

public final class GitHubConnectionRequired extends SecurityException {
    public GitHubConnectionRequired() { super("Current linked GitHub identity required"); }
}
