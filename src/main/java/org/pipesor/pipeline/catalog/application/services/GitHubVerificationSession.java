package org.pipesor.pipeline.catalog.application.services;

import java.util.HashMap;
import java.util.Map;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;

/** Owned by one HTTP request; non-HTTP callers receive a new session per verification. */
public final class GitHubVerificationSession {
    final Map<String, String> identities = new HashMap<>();
    final Map<GitHubConnector.AccessRequest, GitHubConnector.Access> access = new HashMap<>();
}
