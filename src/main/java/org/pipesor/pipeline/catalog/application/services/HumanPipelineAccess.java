package org.pipesor.pipeline.catalog.application.services;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;

/** External preflight precedes SQL write transactions; write handlers recheck local grants under lock. */
public final class HumanPipelineAccess {
    private final PipelineAccess grants;
    private final PipelineLookup pipelines;
    private final GitHubAccessChecks github;

    public HumanPipelineAccess(PipelineAccess grants, PipelineLookup pipelines, GitHubAccessChecks github) {
        this.grants = grants; this.pipelines = pipelines; this.github = github;
    }

    public Set<AccessCapability> require(String actor, UUID pipeline, AccessCapability capability) {
        var local = grants.capabilities(actor, Set.of(pipeline)).getOrDefault(pipeline, Set.of());
        if (!local.contains(capability)) { throw new SecurityException("Pipeline capability required"); }
        var resource = pipelines.findById(pipeline).orElseThrow(NoSuchElementException::new);
        var operation = GitHubConnector.Operation.valueOf(capability.name());
        var proof = github.verify(actor, actor, Set.of(resource.githubRepositoryId()), operation);
        github.require(proof, resource.githubRepositoryId(), capability == AccessCapability.CONFIGURE);
        // The connector may take time; do not reuse a local grant revoked during that preflight.
        local = grants.capabilities(actor, Set.of(pipeline)).getOrDefault(pipeline, Set.of());
        if (!local.contains(capability)) { throw new SecurityException("Pipeline capability revoked"); }
        return effective(local, proof.repositories().get(resource.githubRepositoryId()));
    }

    public void eligibleRecipient(String actor, UUID pipeline, String recipient, AccessCapability capability) {
        var resource = pipelines.findById(pipeline).orElseThrow(NoSuchElementException::new);
        var proof = github.verify(actor, recipient, Set.of(resource.githubRepositoryId()),
                GitHubConnector.Operation.valueOf("GRANT_" + capability.name()));
        github.require(proof, resource.githubRepositoryId(), capability == AccessCapability.CONFIGURE);
    }

    public List<PipelineView> list(String actor, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid offset or limit"); }
        var candidates = pipelines.candidateRepositoryIds(actor);
        // Even an empty candidate set must distinguish unavailable verification from a valid empty list.
        var proof = github.verify(actor, actor, candidates, GitHubConnector.Operation.VIEW);
        var allowed = new HashSet<String>();
        proof.repositories().forEach((id, access) -> {
            if (access.admittedForTenant() && access.readable()) { allowed.add(id); }
        });
        var page = pipelines.listVisible(actor, allowed, offset, limit);
        var capabilities = grants.capabilities(actor, page.stream().map(PipelineView::id).collect(Collectors.toSet()));
        return page.stream().map(row -> row.withCapabilities(effective(capabilities.getOrDefault(row.id(), Set.of()),
                proof.repositories().get(row.githubRepositoryId())))).toList();
    }

    private Set<AccessCapability> effective(Set<AccessCapability> local, GitHubConnector.RepositoryAccess github) {
        if (github == null || !github.admittedForTenant() || !github.readable()) { return Set.of(); }
        var result = new HashSet<>(local);
        if (!github.administrator()) { result.remove(AccessCapability.CONFIGURE); }
        return Set.copyOf(result);
    }
}
