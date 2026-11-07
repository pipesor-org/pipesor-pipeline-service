package org.pipesor.pipeline.configuration;

import java.time.Clock;
import java.util.UUID;
import org.pipesor.pipeline.execution.application.commandhandlers.ApplyExecutionSnapshotCommandHandler;
import org.pipesor.pipeline.execution.application.ports.ExecutionLookup;
import org.pipesor.pipeline.execution.application.ports.SnapshotStore;
import org.pipesor.pipeline.execution.application.queryhandlers.ExecutionQueryHandler;
import org.pipesor.pipeline.execution.application.services.ExecutionQueryService;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.incident.application.ports.IncidentLookup;
import org.pipesor.pipeline.incident.application.ports.IncidentStore;
import org.pipesor.pipeline.incident.application.queryhandlers.IncidentQueryHandler;
import org.pipesor.pipeline.incident.application.services.IncidentQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class OperationsConfiguration {
    @Bean
    ExecutionQueryService executionQueryService(ExecutionLookup lookup) {
        return new ExecutionQueryHandler(lookup);
    }

    @Bean
    IncidentQueryService incidentQueryService(IncidentLookup lookup) {
        return new IncidentQueryHandler(lookup);
    }

    @Bean
    Clock operationsClock() {
        return Clock.systemUTC();
    }

    @Bean
    ExecutionSnapshotService executionSnapshotService(SnapshotStore store, IncidentStore incidents,
            Clock clock, PlatformTransactionManager manager) {
        var handler = new ApplyExecutionSnapshotCommandHandler(store, incidents, clock, UUID::randomUUID);
        var transaction = new TransactionTemplate(manager);
        return command -> transaction.execute(status -> handler.handle(command));
    }
}
