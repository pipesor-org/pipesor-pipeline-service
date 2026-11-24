package org.pipesor.pipeline.catalog.application.commandhandlers;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.application.ports.PipelineGrantStore;
import org.pipesor.pipeline.catalog.application.ports.PipelineSettingsStore;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.LastConfigureGrant;
import org.pipesor.pipeline.catalog.application.services.PipelineAuditMetadata;
import org.pipesor.pipeline.catalog.application.services.PipelineConfigurationService;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.shared.application.ports.AuditLog;

public final class ConfigurePipelineCommandHandler implements PipelineConfigurationService {
    private final PipelineAccess access;
    private final PipelineSettingsStore settings;
    private final PipelineGrantStore grants;
    private final AuditLog audit;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ConfigurePipelineCommandHandler(PipelineAccess access,
            PipelineSettingsStore settings, PipelineGrantStore grants, AuditLog audit, Clock clock, Supplier<UUID> ids) {
        this.access = access;
        this.settings = settings;
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
        this.ids = ids;
    }

    /** Committed settings before and after one configuration command. */
    public record Change(PipelineView before, PipelineView after) {
        public boolean monitoringStatusChanged() { return before.monitoringStatus() != after.monitoringStatus(); }
    }

    @Override
    public PipelineView handle(ConfigurePipelineCommand command) {
        return configure(command).after();
    }

    public Change configure(ConfigurePipelineCommand command) {
        access.require(command.actorRef(), command.pipelineId(), AccessCapability.CONFIGURE);
        var before = settings.lock(command.pipelineId());
        var after = settings.update(command);
        audit.append(ids.get(), "pipeline", command.pipelineId(), command.section() == ConfigurePipelineCommand.Section.ALL ? "CONFIGURE" : "CONFIGURE_" + command.section().name(), command.actorRef(),
                command.correlationId(), clock.instant().truncatedTo(ChronoUnit.MICROS), PipelineAuditMetadata.of(before), PipelineAuditMetadata.of(after));
        return new Change(before, after);
    }

    @Override
    public boolean handle(ChangePipelineAccessCommand command) {
        // Lock before the actor's own grant, so two administrators revoking each other run one after the other.
        grants.lock(command.pipelineId());
        access.require(command.actorRef(), command.pipelineId(), AccessCapability.CONFIGURE);
        // ADR-028: someone must keep CONFIGURE, otherwise nobody could grant access to the pipeline again.
        if (!command.grant() && command.capability() == AccessCapability.CONFIGURE
                && grants.holders(command.pipelineId(), AccessCapability.CONFIGURE).equals(Set.of(command.principalRef()))) {
            throw new LastConfigureGrant();
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean changed = grants.change(command, ids.get(), now);
        if (changed) {
            audit.append(ids.get(), "pipeline", command.pipelineId(), command.grant() ? "GRANT" : "REVOKE",
                    command.actorRef(), command.correlationId(), now,
                    Map.of("granted", Boolean.toString(!command.grant())),
                    Map.of("principalRef", command.principalRef(), "capability", command.capability().name(),
                            "granted", Boolean.toString(command.grant())));
        }
        return changed;
    }

}
