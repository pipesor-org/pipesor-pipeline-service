package org.pipesor.pipeline.catalog.application.services;

import java.time.Clock;
import java.util.Set;
import java.util.function.Supplier;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Access;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.AccessRequest;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Operation;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector.Workflow;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable.Reason;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;

public final class GitHubAccessChecks {
    private final GitHubConnector connector;
    private final Clock clock;
    private final Supplier<GitHubVerificationSession> sessions;

    public GitHubAccessChecks(GitHubConnector connector, Clock clock, Supplier<GitHubVerificationSession> sessions) {
        this.connector = connector; this.clock = clock; this.sessions = sessions;
    }

    public Access verify(String actor, String subject, Set<String> repositories, Operation operation) {
        var request = new AccessRequest(actor, subject, repositories, operation);
        var session = sessions.get();
        var facts = session.access.get(request);
        if (facts == null) {
            try { facts = connector.verifyAccess(request); }
            catch (SecurityException denied) { throw denied; }
            catch (GitHubVerificationUnavailable unavailable) { throw unavailable; }
            catch (RuntimeException unavailable) { throw new GitHubVerificationUnavailable(Reason.CONNECTOR_UNREACHABLE); }
            validate(request, facts);
            session.access.put(request, facts);
        } else { validate(request, facts); }
        rememberIdentity(session, facts);
        return facts;
    }

    public Workflow registration(String actor, String repository, String workflow) {
        Workflow verified;
        try { verified = connector.verifyWorkflow(actor, repository, workflow); }
        catch (SecurityException denied) { throw denied; }
        catch (GitHubVerificationUnavailable unavailable) { throw unavailable; }
        catch (RuntimeException unavailable) { throw new GitHubVerificationUnavailable(Reason.CONNECTOR_UNREACHABLE); }
        if (verified == null || !repository.equals(verified.repositoryId()) || !workflow.equals(verified.workflowId())
                || blank(verified.fullName()) || blank(verified.workflowName()) || blank(verified.workflowFile())) {
            throw new GitHubVerificationUnavailable(Reason.WORKFLOW_FACTS_INCOMPLETE);
        }
        validate(new AccessRequest(actor, actor, Set.of(repository), Operation.REGISTER), verified.access());
        require(verified.access(), repository, true);
        rememberIdentity(sessions.get(), verified.access());
        // Also enforces canonical external identities without accepting client names or paths.
        DeterministicIds.pipeline(verified.repositoryId(), verified.workflowId());
        return verified;
    }

    public void require(Access facts, String repository, boolean administration) {
        var access = facts.repositories().get(repository);
        if (access == null || !access.admittedForTenant() || !access.readable()
                || (administration && !access.administrator())) {
            throw new SecurityException("Current GitHub repository access required");
        }
    }

    private void rememberIdentity(GitHubVerificationSession session, Access facts) {
        var previous = session.identities.putIfAbsent(facts.subjectRef(), facts.githubUserId());
        if (previous != null && !previous.equals(facts.githubUserId())) { throw new GitHubConnectionRequired(); }
    }

    private void validate(AccessRequest request, Access facts) {
        var now = clock.instant();
        if (facts == null || !request.actorRef().equals(facts.actorRef())
                || !request.subjectRef().equals(facts.subjectRef()) || request.operation() != facts.operation()
                || !request.repositoryIds().equals(facts.repositories().keySet())
                || facts.verifiedAt() == null || facts.validUntil() == null
                || !facts.validUntil().isAfter(facts.verifiedAt())) {
            throw new GitHubVerificationUnavailable(Reason.FACTS_BINDING_MISMATCH);
        }
        if (facts.verifiedAt().isAfter(now) || !facts.validUntil().isAfter(now)) {
            throw new GitHubVerificationUnavailable(Reason.FACTS_EXPIRED);
        }
        if (!facts.linked()) { throw new GitHubConnectionRequired(); }
        if (blank(facts.githubUserId()) || !facts.githubUserId().matches("[1-9][0-9]*")) {
            throw new GitHubVerificationUnavailable(Reason.GITHUB_USER_INVALID);
        }
        for (var entry : facts.repositories().entrySet()) {
            var repository = entry.getValue();
            if (!entry.getKey().equals(repository.repositoryId())
                    || (repository.installationId() != null && !repository.installationId().matches("[1-9][0-9]*"))
                    || (repository.accountId() != null && !repository.accountId().matches("[1-9][0-9]*"))
                    || ((repository.readable() || repository.administrator()) && blank(repository.installationId()))) {
                throw new GitHubVerificationUnavailable(Reason.REPOSITORY_FACT_INVALID);
            }
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
