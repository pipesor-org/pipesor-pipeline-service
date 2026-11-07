package org.pipesor.pipeline;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.services.GitHubConnectionRequired;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit test-only facts. Unknown identities/resources are denied; never included in application artifacts. */
public final class GitHubConnectorDouble implements GitHubConnector {
    private record Key(String subject, String repository) {}
    private record Selection(String actor, String repository, String workflow) {}
    private final Clock clock;
    private final Set<String> linked = ConcurrentHashMap.newKeySet();
    private final Map<Key, RepositoryAccess> access = new ConcurrentHashMap<>();
    private final Map<Selection, Workflow> workflows = new ConcurrentHashMap<>();
    final AtomicInteger verifications = new AtomicInteger();
    volatile boolean unavailable;
    volatile UnaryOperator<Access> accessOverride = value -> value;
    volatile UnaryOperator<Workflow> workflowOverride = value -> value;

    public GitHubConnectorDouble(Clock clock) { this.clock = clock; }
    void reset() {
        linked.clear(); access.clear(); workflows.clear(); verifications.set(0); unavailable = false;
        accessOverride = value -> value; workflowOverride = value -> value;
    }
    void allow(String actor, String repository, boolean administrator) {
        linked.add(actor);
        access.put(new Key(actor, repository), new RepositoryAccess(repository, "5001", true, true, administrator));
    }
    void facts(String actor, String repository, RepositoryAccess facts) {
        linked.add(actor); access.put(new Key(actor, repository), facts);
    }
    void unlink(String actor) { linked.remove(actor); }
    void workflow(String actor, String repository, String workflow, String fullName, String defaultBranch,
            String name, String file) {
        workflows.put(new Selection(actor, repository, workflow), new Workflow(null, repository, workflow, fullName, defaultBranch, name, file));
    }

    @Override
    public Access verifyAccess(AccessRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AssertionError("Connector invoked inside a SQL transaction");
        }
        verifications.incrementAndGet();
        if (unavailable) { throw new GitHubVerificationUnavailable(); }
        if (!linked.contains(request.subjectRef())) { throw new GitHubConnectionRequired(); }
        var repositories = new HashMap<String, RepositoryAccess>();
        request.repositoryIds().forEach(id -> repositories.put(id,
                access.getOrDefault(new Key(request.subjectRef(), id), new RepositoryAccess(id, null, false, false, false))));
        var now = clock.instant();
        return accessOverride.apply(new Access(request.actorRef(), request.subjectRef(), request.operation(), "1001", true,
                now, now.plusSeconds(60), repositories));
    }

    @Override
    public Workflow verifyWorkflow(String actor, String repository, String workflow) {
        var verified = verifyAccess(new AccessRequest(actor, actor, Set.of(repository), Operation.REGISTER));
        var metadata = workflows.get(new Selection(actor, repository, workflow));
        if (metadata == null) { throw new SecurityException("Workflow selection unavailable"); }
        return workflowOverride.apply(new Workflow(verified, repository, workflow, metadata.fullName(), metadata.defaultBranch(),
                metadata.workflowName(), metadata.workflowFile()));
    }
}
