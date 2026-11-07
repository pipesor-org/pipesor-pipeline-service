package org.pipesor.pipeline.catalog.application.commandhandlers;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.commands.RegisterPipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.ports.PipelineGrantStore;
import org.pipesor.pipeline.catalog.application.ports.PipelineRegistrationStore;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.pipesor.pipeline.catalog.application.services.PipelineAuditMetadata;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.shared.application.ports.AuditLog;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;

public final class RegisterPipelineCommandHandler {
    private final PipelineRegistrationStore store;
    private final PipelineGrantStore grants;
    private final AuditLog audit;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public RegisterPipelineCommandHandler(PipelineRegistrationStore store, PipelineGrantStore grants,
            AuditLog audit, Clock clock, Supplier<UUID> ids) {
        this.store = store; this.grants = grants; this.audit = audit; this.clock = clock; this.ids = ids;
    }

    /** The composition validates connector facts before starting this local transaction. */
    public PipelineView handle(RegisterPipelineCommand command, GitHubConnector.Workflow verified) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var pipeline = store.register(command, verified, ids.get(),
                DeterministicIds.pipeline(command.githubRepositoryId(), command.githubWorkflowId()), now);
        if (!verified.access().validUntil().isAfter(clock.instant())) { throw new GitHubVerificationUnavailable(); }
        grants.change(new ChangePipelineAccessCommand(pipeline.id(), command.actorRef(), command.correlationId(),
                command.actorRef(), AccessCapability.CONFIGURE, true), ids.get(), now);
        var after = new LinkedHashMap<>(PipelineAuditMetadata.of(pipeline));
        after.put("repositoryId", pipeline.repositoryId().toString());
        after.put("githubRepositoryId", pipeline.githubRepositoryId());
        after.put("githubWorkflowId", pipeline.githubWorkflowId());
        after.put("installationId", verified.access().repositories().get(pipeline.githubRepositoryId()).installationId());
        after.put("creatorCapability", "CONFIGURE");
        audit.append(ids.get(), "pipeline", pipeline.id(), "REGISTER", command.actorRef(), command.correlationId(),
                now, Map.of(), after);
        return pipeline.withCapabilities(Set.of(AccessCapability.VIEW, AccessCapability.CONFIGURE));
    }
}
