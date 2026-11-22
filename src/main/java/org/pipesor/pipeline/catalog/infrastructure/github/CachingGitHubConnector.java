package org.pipesor.pipeline.catalog.infrastructure.github;

import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reuses a successful VIEW access verification for a short, bounded time (ADR-020 amendment, MVP).
 *
 * <p>Every human read re-verifies current GitHub access through Ingestion (several GitHub API calls, ~1.7 s).
 * Dashboard lists repeat the same check per row, which is slow and can exhaust the user's GitHub rate limit.
 * Only {@link Operation#VIEW} is cached: commands (OPERATE, CONFIGURE, grants, registration) are always verified
 * live. A revocation in GitHub therefore takes at most {@code ttl} to affect reads. Failures are never cached.
 */
public final class CachingGitHubConnector implements GitHubConnector {
    static final int MAX_ENTRIES = 1024;
    private final GitHubConnector delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<AccessRequest, Access> views = new ConcurrentHashMap<>();

    public CachingGitHubConnector(GitHubConnector delegate, Duration ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate);
        this.ttl = Objects.requireNonNull(ttl);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isNegative() || ttl.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("The VIEW access cache TTL must be between 0 and 60 seconds");
        }
    }

    @Override
    public Access verifyAccess(AccessRequest request) {
        if (ttl.isZero() || request.operation() != Operation.VIEW) return delegate.verifyAccess(request);
        var now = clock.instant();
        var cached = views.get(request);
        if (cached != null) {
            var expires = cached.verifiedAt().plus(ttl);
            if (!cached.verifiedAt().isAfter(now) && now.isBefore(expires)) {
                // The facts are accepted until verifiedAt + ttl, the policy this cache implements.
                return new Access(cached.actorRef(), cached.subjectRef(), cached.operation(), cached.githubUserId(),
                        cached.linked(), cached.verifiedAt(), expires, cached.repositories());
            }
            views.remove(request, cached);
        }
        var fresh = delegate.verifyAccess(request);
        if (fresh != null && fresh.linked() && fresh.verifiedAt() != null) {
            if (views.size() >= MAX_ENTRIES) evictExpired(now);
            if (views.size() < MAX_ENTRIES) views.put(request, fresh);
        }
        return fresh;
    }

    @Override
    public Workflow verifyWorkflow(String actorRef, String repositoryId, String workflowId) {
        return delegate.verifyWorkflow(actorRef, repositoryId, workflowId);
    }

    private void evictExpired(java.time.Instant now) {
        views.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().verifiedAt().plus(ttl)));
    }
}
