package org.pipesor.pipeline.configuration;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commandhandlers.ConfigurePipelineCommandHandler;
import org.pipesor.pipeline.catalog.application.commandhandlers.RegisterPipelineCommandHandler;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.application.ports.PipelineAdministrationLookup;
import org.pipesor.pipeline.catalog.application.ports.PipelineGrantStore;
import org.pipesor.pipeline.catalog.application.ports.PipelineLookup;
import org.pipesor.pipeline.catalog.application.ports.PipelineRegistrationStore;
import org.pipesor.pipeline.catalog.application.ports.PipelineSettingsStore;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.queryhandlers.PipelineAdministrationQueryHandler;
import org.pipesor.pipeline.catalog.application.queryhandlers.PipelineQueryHandler;
import org.pipesor.pipeline.catalog.application.services.GitHubAccessChecks;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.application.services.PipelineAdministrationQueryService;
import org.pipesor.pipeline.catalog.application.services.PipelineConfigurationService;
import org.pipesor.pipeline.catalog.application.services.PipelineQueryService;
import org.pipesor.pipeline.catalog.application.services.PipelineRegistrationService;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.execution.application.commandhandlers.ApplyExecutionSnapshotCommandHandler;
import org.pipesor.pipeline.execution.application.ports.ExecutionLookup;
import org.pipesor.pipeline.execution.application.ports.SnapshotStore;
import org.pipesor.pipeline.execution.application.queryhandlers.ExecutionQueryHandler;
import org.pipesor.pipeline.execution.application.services.ExecutionQueryService;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.incident.application.commandhandlers.OpenIncidentCommandHandler;
import org.pipesor.pipeline.incident.application.commandhandlers.TransitionIncidentCommandHandler;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.ports.IncidentOriginLookup;
import org.pipesor.pipeline.incident.application.ports.IncidentStore;
import org.pipesor.pipeline.incident.application.ports.VerificationLookup;
import org.pipesor.pipeline.incident.application.queryhandlers.DiagnosisEntitlementQueryHandler;
import org.pipesor.pipeline.incident.application.queryhandlers.IncidentQueryHandler;
import org.pipesor.pipeline.incident.application.queryhandlers.IncidentQueueQueryHandler;
import org.pipesor.pipeline.incident.application.services.DiagnosisEntitlementService;
import org.pipesor.pipeline.incident.application.services.IncidentQueueService;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.incident.application.services.IncidentLifecycleService;
import org.pipesor.pipeline.incident.application.services.IncidentQueryService;
import org.pipesor.pipeline.shared.application.ports.AuditLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class OperationsConfiguration {
    @Bean
    PipelineQueryService pipelineQueryService(PipelineLookup lookup, HumanPipelineAccess human) {
        return new PipelineQueryHandler(lookup, human);
    }

    @Bean
    ExecutionQueryService executionQueryService(ExecutionLookup lookup) {
        return new ExecutionQueryHandler(lookup);
    }

    @Bean
    IncidentQueryService incidentQueryService(IncidentLookup lookup) {
        return new IncidentQueryHandler(lookup);
    }

    @Bean
    IncidentQueueService incidentQueueService(HumanPipelineAccess human, IncidentLookup lookup) {
        return new IncidentQueueQueryHandler(human::visibleRepositoryIds, lookup);
    }

    @Bean
    DiagnosisEntitlementService diagnosisEntitlementService(IncidentLookup lookup, HumanPipelineAccess human) {
        return new DiagnosisEntitlementQueryHandler(lookup, human);
    }

    @Bean
    Clock operationsClock() {
        return Clock.systemUTC();
    }

    @Bean
    IncidentCommandService incidentCommandService(IncidentOriginLookup executions, IncidentStore incidents,
                                                 Clock clock, PlatformTransactionManager manager) {
        var handler = new OpenIncidentCommandHandler(executions, incidents, clock, UUID::randomUUID);
        var transaction = new TransactionTemplate(manager);
        return command -> transaction.execute(status -> handler.handle(command));
    }
    @Bean
    IncidentLifecycleService incidentLifecycleService(
            IncidentStore incidents,
            VerificationLookup executions,
            IncidentLookup incidentQueries, ExecutionLookup executionQueries,
            HumanPipelineAccess human,
            PipelineAccess access,
            AuditLog audit,
            Clock clock, PlatformTransactionManager manager) {
        var handler = new TransitionIncidentCommandHandler(
                incidents, executions, access, audit, clock, UUID::randomUUID);
        var transaction = new TransactionTemplate(manager);
        return command -> {
            var incident = incidentQueries.findById(command.incidentId()).orElseThrow(NoSuchElementException::new);
            human.require(command.actorRef(), incident.pipelineId(), AccessCapability.OPERATE);
            if (command.executionId() != null) {
                var execution = executionQueries.findById(command.executionId()).orElseThrow(NoSuchElementException::new);
                human.require(command.actorRef(), execution.pipelineId(), AccessCapability.VIEW);
            }
            return transaction.execute(status -> handler.handle(command));
        };
    }

    @Bean
    PipelineConfigurationService pipelineConfigurationService(
            HumanPipelineAccess human,
            PipelineAccess access,
            PipelineSettingsStore settings,
            PipelineGrantStore grants,
            AuditLog audit, CaptureScopeSync scopes,
            Clock clock, PlatformTransactionManager manager) {
        var handler = new ConfigurePipelineCommandHandler(
                access, settings, grants, audit, clock, UUID::randomUUID);
        var transaction = new TransactionTemplate(manager);
        return new PipelineConfigurationService() {
            @Override
            public PipelineView handle(
                    ConfigurePipelineCommand command) {
                var capabilities = human.require(command.actorRef(), command.pipelineId(), AccessCapability.CONFIGURE);
                var change = transaction.execute(status -> handler.configure(command));
                // ADR-023: the committed status is pushed to Ingestion after the transaction, never inside it.
                if (change.monitoringStatusChanged()) {
                    CaptureScopeConfiguration.afterCommit(scopes, change.after(), command.correlationId());
                }
                return change.after().withCapabilities(capabilities);
            }

            @Override
            public boolean handle(ChangePipelineAccessCommand command) {
                human.require(command.actorRef(), command.pipelineId(), AccessCapability.CONFIGURE);
                if (command.grant()) { human.eligibleRecipient(command.actorRef(), command.pipelineId(), command.principalRef(), command.capability()); }
                return transaction.execute(status -> handler.handle(command));
            }
        };
    }

    @Bean
    ExecutionSnapshotService executionSnapshotService(
            SnapshotStore store, IncidentStore incidents,
            Clock clock, PlatformTransactionManager manager) {
        var handler = new ApplyExecutionSnapshotCommandHandler(
                store, incidents, clock, UUID::randomUUID);
        var transaction = new TransactionTemplate(manager);
        return command -> transaction.execute(status -> handler.handle(command));
    }

    @Bean
    PipelineRegistrationService pipelineRegistrationService(
            GitHubAccessChecks github,
            PipelineRegistrationStore store,
            PipelineGrantStore grants, AuditLog audit, CaptureScopeSync scopes, Clock clock,
            PlatformTransactionManager manager) {
        var handler = new RegisterPipelineCommandHandler(store, grants, audit, clock, UUID::randomUUID);
        var transaction = new TransactionTemplate(manager);
        return command -> {
            var verified = github.registration(command.actorRef(), command.githubRepositoryId(), command.githubWorkflowId());
            var created = transaction.execute(status -> handler.handle(command, verified));
            // ADR-023: makes the committed registration capturable; a failure leaves it to reconciliation.
            CaptureScopeConfiguration.afterCommit(scopes, created, command.correlationId());
            return created;
        };
    }

    @Bean
    PipelineAdministrationQueryService pipelineAdministrationQueryService(
            HumanPipelineAccess human,
            PipelineAdministrationLookup lookup) {
        return new PipelineAdministrationQueryHandler(human, lookup);
    }

}
