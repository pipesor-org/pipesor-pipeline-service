package org.pipesor.pipeline;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.PipelineAccess;
import org.pipesor.pipeline.catalog.application.queries.GetPipelineQuery;
import org.pipesor.pipeline.catalog.application.queries.ListPipelinesQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.PipelineConfigurationService;
import org.pipesor.pipeline.catalog.application.services.PipelineQueryService;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;
import org.pipesor.pipeline.execution.application.queries.ExecutionStageView;
import org.pipesor.pipeline.execution.application.queries.ExecutionView;
import org.pipesor.pipeline.execution.application.queries.GetExecutionQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionStagesQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionsQuery;
import org.pipesor.pipeline.execution.application.services.ExecutionQueryService;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.commands.TransitionIncidentCommand;
import org.pipesor.pipeline.incident.application.ports.IncidentOriginLookup;
import org.pipesor.pipeline.incident.application.queries.GetIncidentQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentRunsQuery;
import org.pipesor.pipeline.incident.application.queries.ListIncidentsQuery;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.incident.application.services.IncidentLifecycleService;
import org.pipesor.pipeline.incident.application.services.IncidentQueryService;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentAction;
import org.pipesor.pipeline.incident.domain.model.valueobjects.InvalidIncidentTransition;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentRunRole;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentStatus;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Import({OperationalServiceTests.TimeConfiguration.class, GitHubConnectorTestConfiguration.class})
class OperationalServiceTests extends PostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DataSource dataSource;
    @Autowired private PipelineQueryService pipelines;
    @Autowired private ExecutionQueryService executions;
    @Autowired private IncidentCommandService commands;
    @Autowired private IncidentQueryService incidents;
    @Autowired private IncidentOriginLookup origins;

    @Autowired private IncidentLifecycleService lifecycle;
    @Autowired private PipelineConfigurationService configuration;
    @Autowired private PipelineAccess access;
    @Autowired private ControlledClock time;
    @Autowired private GitHubConnectorDouble github;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary
        ControlledClock controlledClock() { return new ControlledClock(); }
    }

    static class ControlledClock extends Clock {
        private Instant now;
        void advance() { now = now.plusSeconds(10); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private final Instant observed = Instant.parse("2026-11-06T12:34:56.123456Z");
    private UUID repositoryId;
    private UUID pipelineId;
    private UUID failedId;
    private UUID passedId;
    private UUID otherRepositoryId;
    private UUID otherPipelineId;
    private UUID otherExecutionId;

    @BeforeEach
    void seedPersistedOperationalContextUsingJdbc() {
        time.now = observed.plusSeconds(3600);
        github.reset();
        repositoryId = UUID.randomUUID();
        pipelineId = UUID.randomUUID();
        failedId = UUID.randomUUID();
        passedId = UUID.randomUUID();
        otherRepositoryId = UUID.randomUUID();
        otherPipelineId = UUID.randomUUID();
        otherExecutionId = UUID.randomUUID();
        insertRepository(repositoryId, "first/service");
        insertRepository(otherRepositoryId, "other/service");
        for (String actor : new String[] {"operator", "reader", "configurer", "new-operator", "new-reader"}) {
            github.allow(actor, repositoryId.toString(), true);
            github.allow(actor, otherRepositoryId.toString(), true);
        }
        insertPipeline(pipelineId, repositoryId, "Build workflow");
        insertPipeline(otherPipelineId, otherRepositoryId, "Other workflow");
        insertExecution(failedId, pipelineId, "12345678901234567890", true);
        insertExecution(passedId, pipelineId, "98765432109876543210", false);
        insertExecution(otherExecutionId, otherPipelineId, "other-run", true);
        jdbc.update("""
                INSERT INTO ops.execution_stages (id, execution_id, stage, external_status, correlation_id, created_at)
                VALUES (?, ?, 'TESTING', 'completed', 'stage-correlation', ?),
                       (?, ?, 'DEPLOYMENT', 'queued', 'other-correlation', ?)
                """, UUID.randomUUID(), failedId, Timestamp.from(observed),
                UUID.randomUUID(), otherExecutionId, Timestamp.from(observed.plusSeconds(1)));
    }

    @AfterEach
    void removeOnlyThisTestsOperationalData() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM audit.audit_events WHERE correlation_id LIKE 'lifecycle-%' OR (entity_type = 'pipeline' AND entity_id IN (?, ?))", pipelineId.toString(), otherPipelineId.toString());
            jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id IN (?, ?)", pipelineId, otherPipelineId);
            jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id IN (?, ?))", pipelineId, otherPipelineId);
            jdbc.update("DELETE FROM ops.incidents WHERE pipeline_id IN (?, ?)", pipelineId, otherPipelineId);
            jdbc.update("DELETE FROM ops.execution_stages WHERE execution_id IN (SELECT id FROM ops.pipeline_executions WHERE pipeline_id IN (?, ?))", pipelineId, otherPipelineId);
            jdbc.update("DELETE FROM ops.pipeline_executions WHERE pipeline_id IN (?, ?)", pipelineId, otherPipelineId);
            jdbc.update("DELETE FROM ops.pipelines WHERE repository_id IN (?, ?)", repositoryId, otherRepositoryId);
            jdbc.update("DELETE FROM ops.repositories WHERE id IN (?, ?)", repositoryId, otherRepositoryId);
        });
    }

    @Test
    void queriesPreserveOperationalMetadataAndScopePagesToTheirParent() {
        var pipeline = pipelines.handle(new GetPipelineQuery(pipelineId)).orElseThrow();
        assertThat(pipeline.workflowName()).isEqualTo("Build workflow");
        assertThat(pipeline.githubWorkflowId()).isEqualTo("shared-external-workflow");
        assertThat(pipeline.repositoryId()).isEqualTo(repositoryId);
        assertThat(pipeline.createdAt()).isEqualTo(observed);
        assertThat(pipelines.handle(new ListPipelinesQuery(repositoryId, 0, 100)))
                .extracting(PipelineView::id).containsExactly(pipelineId);

        var execution = executions.handle(new GetExecutionQuery(failedId)).orElseThrow();
        assertThat(execution.githubRunId()).isEqualTo("12345678901234567890");
        assertThat(execution.branch()).isEqualTo("feature/operational-core");
        assertThat(execution.runAttempt()).isEqualTo(2);
        assertThat(execution.startedAt()).isEqualTo(observed);
        assertThat(execution.failed()).isTrue();
        assertThat(executions.handle(new ListExecutionsQuery(pipelineId, 0, 100)))
                .extracting(ExecutionView::id).containsExactlyInAnyOrder(failedId, passedId);
        var page = executions.handle(new ListExecutionsQuery(pipelineId, 0, 1));
        var next = executions.handle(new ListExecutionsQuery(pipelineId, 1, 1));
        assertThat(page).hasSize(1);
        assertThat(next).hasSize(1).doesNotContainAnyElementsOf(page);
        assertThat(executions.handle(new ListExecutionStagesQuery(failedId)))
                .extracting(ExecutionStageView::stage).containsExactly(PipelineStage.TESTING);
        assertThat(executions.handle(new GetExecutionQuery(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void createsAndCommitsAnIncidentAndItsOriginThroughTheRealApplicationService() {
        var created = commands.handle(command(pipelineId, failedId));

        var stored = incidents.handle(new GetIncidentQuery(created.getId())).orElseThrow();
        assertThat(stored.pipelineId()).isEqualTo(pipelineId);
        assertThat(stored.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(stored.severity()).isNull();
        assertThat(stored.stage()).isEqualTo(PipelineStage.TESTING);
        assertThat(stored.summary()).isEqualTo("Sanitized test failure");
        assertThat(stored.detectedAt()).isEqualTo(observed);
        assertThat(stored.createdAt()).isEqualTo(created.getDetails().createdAt());
        assertThat(stored.correlationId()).isEqualTo("incident-correlation");
        var runs = incidents.handle(new ListIncidentRunsQuery(created.getId(), 0, 100));
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().executionId()).isEqualTo(failedId);
        assertThat(runs.getFirst().role()).isEqualTo(IncidentRunRole.ORIGIN);
        assertThat(runs.getFirst().createdAt()).isEqualTo(stored.createdAt());
        assertThat(incidents.handle(new ListIncidentsQuery(pipelineId, 0, 100)))
                .extracting(IncidentView::id).containsExactly(created.getId());
        assertThat(incidents.handle(new ListIncidentsQuery(otherPipelineId, 0, 100))).isEmpty();
    }

    @Test
    void rejectsMissingSuccessfulOrWrongPipelineOriginsWithoutWritingAnIncident() {
        assertThrows(NoSuchElementException.class, () -> commands.handle(command(pipelineId, UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> commands.handle(command(pipelineId, passedId)));
        assertThrows(IllegalArgumentException.class, () -> commands.handle(command(otherPipelineId, failedId)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incidents WHERE pipeline_id IN (?, ?)",
                Integer.class, pipelineId, otherPipelineId)).isZero();
    }

    @Test
    void rollsBackTheIncidentWhenItsOriginInsertFails() {
        jdbc.execute("""
                CREATE FUNCTION ops.test_reject_origin() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced origin rejection' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_reject_origin BEFORE INSERT ON ops.incident_runs FOR EACH ROW EXECUTE FUNCTION ops.test_reject_origin()");
        try {
            var error = assertThrows(RuntimeException.class, () -> commands.handle(command(pipelineId, failedId)));
            assertThat(postgresCause(error).getSQLState()).isEqualTo("23514");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incidents WHERE pipeline_id = ?", Integer.class, pipelineId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incident_runs WHERE execution_id = ?", Integer.class, failedId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_origin ON ops.incident_runs");
            jdbc.execute("DROP FUNCTION ops.test_reject_origin()");
        }
    }

    @Test
    void databaseRejectsMissingNonFailedAndWrongPipelineOriginsAtCommit() {
        var transaction = new TransactionTemplate(manager);
        for (UUID origin : new UUID[] {null, passedId, otherExecutionId}) {
            var error = assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
                UUID incident = UUID.randomUUID();
                insertIncident(incident);
                if (origin != null) {
                    insertRun(incident, origin, "ORIGIN");
                }
            }));
            assertThat(postgresCause(error).getSQLState()).isEqualTo("23514");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.incidents WHERE pipeline_id = ?", Integer.class, pipelineId)).isZero();
    }

    @Test
    void databaseProtectsTheSingleOriginAndItsFailureFact() {
        var created = commands.handle(command(pipelineId, failedId));
        var duplicate = assertThrows(RuntimeException.class, () -> insertRun(created.getId(), passedId, "ORIGIN"));
        assertThat(postgresCause(duplicate).getSQLState()).isEqualTo("23505");
        assertThat(postgresCause(duplicate).getServerErrorMessage().getConstraint()).isEqualTo("incident_runs_one_origin_uq");
        var changed = assertThrows(RuntimeException.class, () -> jdbc.update("UPDATE ops.pipeline_executions SET failed = false WHERE id = ?", failedId));
        assertThat(postgresCause(changed).getSQLState()).isEqualTo("23514");
        var truncated = assertThrows(RuntimeException.class, () -> jdbc.execute("TRUNCATE ops.incident_runs"));
        assertThat(postgresCause(truncated).getSQLState()).isEqualTo("23514");
    }

    @Test
    void verificationStorageDoesNotInventAnOriginPipelineRestriction() {
        var created = commands.handle(command(pipelineId, failedId));
        insertRun(created.getId(), otherExecutionId, "VERIFICATION");

        assertThat(incidents.handle(new ListIncidentRunsQuery(created.getId(), 0, 100)))
                .extracting(IncidentRunView::executionId).containsExactlyInAnyOrder(failedId, otherExecutionId);
    }

    @Test
    void locksTheAuthoritativeExecutionAgainstConcurrentFailureChanges() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            origins.lockExecution(failedId).orElseThrow();
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SET lock_timeout = '200ms'");
                var blocked = assertThrows(SQLException.class,
                        () -> statement.executeUpdate("UPDATE ops.pipeline_executions SET failed = false WHERE id = '" + failedId + "'"));
                assertThat(blocked.getSQLState()).isEqualTo("55P03");
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
            commands.handle(command(pipelineId, failedId));
        });
        assertThat(incidents.handle(new ListIncidentsQuery(pipelineId, 0, 100))).hasSize(1);
    }

    @Test
    void resolvesWithHumanConfirmationAndSuccessfulAuthorizedCrossPipelineVerification() {
        var incident = commands.handle(command(pipelineId, failedId));
        grant(pipelineId, "operator", "OPERATE");
        transition(incident.getId(), IncidentAction.START_REMEDIATION, null, "operator");
        prepareVerification(otherExecutionId, false, "in_progress", null);
        assertThrows(SecurityException.class, () -> transition(incident.getId(), IncidentAction.START_VERIFICATION, otherExecutionId, "operator"));
        assertThat(stored(incident.getId()).status()).isEqualTo(IncidentStatus.IN_REMEDIATION);

        grant(otherPipelineId, "operator", "VIEW");
        transition(incident.getId(), IncidentAction.START_VERIFICATION, otherExecutionId, "operator");
        assertThrows(IllegalArgumentException.class, () -> transition(incident.getId(), IncidentAction.RESOLVE, otherExecutionId, "operator"));
        assertThat(stored(incident.getId()).status()).isEqualTo(IncidentStatus.VERIFICATION);
        jdbc.update("UPDATE ops.pipeline_executions SET external_status = 'completed', external_conclusion = 'success', completed_at = ? WHERE id = ?",
                Timestamp.from(time.instant()), otherExecutionId);
        transition(incident.getId(), IncidentAction.RESOLVE, otherExecutionId, "operator");

        var resolved = stored(incident.getId());
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(time.instant());
        assertThat(resolved.summary()).isEqualTo("Sanitized test failure");
        assertThat(incidents.handle(new ListIncidentRunsQuery(incident.getId(), 0, 100)))
                .extracting(IncidentRunView::executionId).containsExactlyInAnyOrder(failedId, otherExecutionId);
        assertThrows(InvalidIncidentTransition.class, () -> transition(incident.getId(), IncidentAction.START_REMEDIATION, null, "operator"));
        var history = jdbc.queryForList("""
                SELECT action, actor_ref, before_state->>'status' AS old_status,
                       after_state->>'status' AS new_status, after_state->>'executionId' AS execution_id
                FROM audit.audit_events WHERE entity_id = ? ORDER BY occurred_at
                """, incident.getId().toString());
        assertThat(history).hasSize(3);
        assertThat(history).extracting(row -> row.get("actor_ref")).containsOnly("operator");
        assertThat(history).extracting(row -> row.get("old_status")).containsExactly("OPEN", "IN_REMEDIATION", "VERIFICATION");
        assertThat(history).extracting(row -> row.get("new_status")).containsExactly("IN_REMEDIATION", "VERIFICATION", "RESOLVED");
        assertThat(history.getLast().get("execution_id")).isEqualTo(otherExecutionId.toString());
    }

    @Test
    void failedVerificationReturnsToRemediationAndKeepsHistoryWithoutReusingOldEvidence() {
        var incident = commands.handle(command(pipelineId, failedId));
        grant(pipelineId, "operator", "OPERATE");
        grant(otherPipelineId, "operator", "VIEW");
        transition(incident.getId(), IncidentAction.START_REMEDIATION, null, "operator");
        assertThrows(IllegalArgumentException.class, () -> transition(incident.getId(), IncidentAction.START_VERIFICATION, passedId, "operator"));
        prepareVerification(otherExecutionId, true, "completed", "failure");
        transition(incident.getId(), IncidentAction.START_VERIFICATION, otherExecutionId, "operator");
        assertThrows(IllegalArgumentException.class, () -> transition(incident.getId(), IncidentAction.RESOLVE, otherExecutionId, "operator"));
        transition(incident.getId(), IncidentAction.RETRY_REMEDIATION, otherExecutionId, "operator");
        assertThrows(IllegalArgumentException.class, () -> transition(incident.getId(), IncidentAction.START_VERIFICATION, otherExecutionId, "operator"));
        prepareVerification(passedId, false, "completed", "success");
        transition(incident.getId(), IncidentAction.START_VERIFICATION, passedId, "operator");
        assertThrows(IllegalArgumentException.class, () -> transition(incident.getId(), IncidentAction.RESOLVE, otherExecutionId, "operator"));
        transition(incident.getId(), IncidentAction.RESOLVE, passedId, "operator");

        assertThat(stored(incident.getId()).status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incidents.handle(new ListIncidentRunsQuery(incident.getId(), 0, 100)))
                .extracting(IncidentRunView::executionId).containsExactlyInAnyOrder(failedId, otherExecutionId, passedId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events WHERE entity_id = ?", Integer.class, incident.getId().toString())).isEqualTo(5);
    }

    @Test
    void deniesByDefaultAndKeepsOperateAndConfigureIndependentPerPipeline() {
        var incident = commands.handle(command(pipelineId, failedId));
        assertThrows(SecurityException.class, () -> transition(incident.getId(), IncidentAction.START_REMEDIATION, null, "unknown"));
        grant(pipelineId, "reader", "VIEW");
        grant(pipelineId, "configurer", "CONFIGURE");
        grant(pipelineId, "operator", "OPERATE");
        for (String actor : new String[] {"reader", "configurer", "operator"}) {
            access.require(actor, pipelineId, AccessCapability.VIEW);
        }
        for (String actor : new String[] {"reader", "configurer"}) {
            assertThrows(SecurityException.class, () -> transition(incident.getId(), IncidentAction.START_REMEDIATION, null, actor));
        }
        assertThrows(SecurityException.class, () -> access.require("operator", pipelineId, AccessCapability.CONFIGURE));
        assertThrows(SecurityException.class, () -> access.require("operator", otherPipelineId, AccessCapability.VIEW));
        assertThat(stored(incident.getId()).status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events WHERE entity_id = ?", Integer.class, incident.getId().toString())).isZero();
    }

    @Test
    void configurerEditsMetadataAndManagesExactCapabilitiesWithoutGrantingOperateImplicitly() {
        grant(pipelineId, "configurer", "CONFIGURE");
        grant(pipelineId, "operator", "OPERATE");
        var command = new ConfigurePipelineCommand(pipelineId, "configurer", "lifecycle-configure",
                PipelineCriticality.CRITICAL, MonitoringStatus.ACTIVE, "dev", "team-ref", "Platform");
        assertThrows(SecurityException.class, () -> configuration.handle(new ConfigurePipelineCommand(pipelineId,
                "operator", "lifecycle-denied", PipelineCriticality.LOW, MonitoringStatus.PAUSED, null, null, null)));
        var edited = configuration.handle(command);
        assertThat(edited.criticality()).isEqualTo(PipelineCriticality.CRITICAL);
        assertThat(edited.monitoringStatus()).isEqualTo(MonitoringStatus.ACTIVE);
        assertThat(pipelines.handle(new GetPipelineQuery(pipelineId)).orElseThrow())
                .usingRecursiveComparison().ignoringFields("effectiveCapabilities").isEqualTo(edited);
        assertThat(edited.responsibleTeamRef()).isEqualTo("team-ref");
        assertThat(edited.environment()).isEqualTo("dev");
        assertThat(edited.repositoryId()).isEqualTo(repositoryId);
        assertThat(edited.createdAt()).isEqualTo(observed);
        var add = new ChangePipelineAccessCommand(pipelineId, "configurer", "lifecycle-grant", "new-operator", AccessCapability.OPERATE, true);
        assertThat(configuration.handle(add)).isTrue();
        assertThat(configuration.handle(add)).isFalse();
        access.require("new-operator", pipelineId, AccessCapability.OPERATE);
        access.require("new-operator", pipelineId, AccessCapability.VIEW);
        assertThrows(SecurityException.class, () -> access.require("new-operator", pipelineId, AccessCapability.CONFIGURE));
        var remove = new ChangePipelineAccessCommand(pipelineId, "configurer", "lifecycle-revoke", "new-operator", AccessCapability.OPERATE, false);
        assertThat(configuration.handle(remove)).isTrue();
        assertThat(configuration.handle(remove)).isFalse();
        assertThrows(SecurityException.class, () -> access.require("new-operator", pipelineId, AccessCapability.VIEW));
        assertThat(jdbc.queryForList("SELECT action FROM audit.audit_events WHERE entity_id = ? ORDER BY created_at, action", String.class, pipelineId.toString()))
                .containsExactlyInAnyOrder("CONFIGURE", "GRANT", "REVOKE");
    }

    @Test
    void auditFailureRollsBackLifecycleAndVerificationLinkAndConfiguration() {
        var incident = commands.handle(command(pipelineId, failedId));
        grant(pipelineId, "operator", "OPERATE");
        grant(pipelineId, "operator", "CONFIGURE");
        transition(incident.getId(), IncidentAction.START_REMEDIATION, null, "operator");
        prepareVerification(passedId, false, "completed", "success");
        jdbc.execute("""
                CREATE FUNCTION audit.test_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced audit rejection' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_reject_event BEFORE INSERT ON audit.audit_events FOR EACH ROW EXECUTE FUNCTION audit.test_reject_event()");
        try {
            var error = assertThrows(RuntimeException.class, () -> transition(incident.getId(), IncidentAction.START_VERIFICATION, passedId, "operator"));
            assertThat(postgresCause(error).getSQLState()).isEqualTo("23514");
            assertThat(stored(incident.getId()).status()).isEqualTo(IncidentStatus.IN_REMEDIATION);
            assertThat(incidents.handle(new ListIncidentRunsQuery(incident.getId(), 0, 100))).hasSize(1);
            var settingsFailure = assertThrows(RuntimeException.class, () -> configuration.handle(new ConfigurePipelineCommand(pipelineId,
                    "operator", "lifecycle-rollback", PipelineCriticality.LOW, MonitoringStatus.ACTIVE, "changed", null, null)));
            assertThat(postgresCause(settingsFailure).getSQLState()).isEqualTo("23514");
            assertThat(pipelines.handle(new GetPipelineQuery(pipelineId)).orElseThrow().criticality()).isEqualTo(PipelineCriticality.HIGH);
            var grantFailure = assertThrows(RuntimeException.class, () -> configuration.handle(new ChangePipelineAccessCommand(pipelineId,
                    "operator", "lifecycle-rollback-grant", "new-reader", AccessCapability.VIEW, true)));
            assertThat(postgresCause(grantFailure).getSQLState()).isEqualTo("23514");
            assertThrows(SecurityException.class, () -> access.require("new-reader", pipelineId, AccessCapability.VIEW));
        } finally {
            jdbc.execute("DROP TRIGGER test_reject_event ON audit.audit_events");
            jdbc.execute("DROP FUNCTION audit.test_reject_event()");
        }
    }

    @Test
    void concurrentOperatorsCommitOneTransitionAndOneAuditEvent() throws Exception {
        var incident = commands.handle(command(pipelineId, failedId));
        grant(pipelineId, "operator", "OPERATE");
        var ready = new CountDownLatch(2);
        Callable<Boolean> attempt = () -> {
            ready.countDown();
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent command did not start");
            }
            try {
                lifecycle.handle(new TransitionIncidentCommand(incident.getId(), IncidentAction.START_REMEDIATION,
                        null, "operator", "lifecycle-concurrent"));
                return true;
            } catch (InvalidIncidentTransition rejectedTransition) {
                return false;
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(stored(incident.getId()).status()).isEqualTo(IncidentStatus.IN_REMEDIATION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events WHERE entity_id = ?", Integer.class, incident.getId().toString())).isEqualTo(1);
    }

    private void transition(UUID incident, IncidentAction action, UUID execution, String actor) {
        time.advance();
        lifecycle.handle(new TransitionIncidentCommand(incident, action, execution, actor, "lifecycle-" + incident));
    }

    private IncidentView stored(UUID id) {
        return incidents.handle(new GetIncidentQuery(id)).orElseThrow();
    }

    private void grant(UUID pipeline, String actor, String capability) {
        jdbc.update("""
                INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                VALUES (?, ?, ?, ?::ops."AccessCapability", 'fixture', ?)
                """, UUID.randomUUID(), pipeline, actor, capability, Timestamp.from(time.instant()));
    }

    private void prepareVerification(UUID execution, boolean failed, String status, String conclusion) {
        time.advance();
        jdbc.update("UPDATE ops.pipeline_executions SET started_at = ?, completed_at = ?, failed = ?, external_status = ?, external_conclusion = ? WHERE id = ?",
                Timestamp.from(time.instant()), status.equals("completed") ? Timestamp.from(time.instant().plusSeconds(1)) : null,
                failed, status, conclusion, execution);
    }

    private OpenIncidentCommand command(UUID pipeline, UUID execution) {
        return new OpenIncidentCommand(pipeline, execution, PipelineStage.TESTING,
                "Sanitized test failure", observed, "incident-correlation");
    }

    private void insertRepository(UUID id, String name) {
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, ?, ?)",
                id, id.toString(), name, Timestamp.from(observed));
    }

    private void insertPipeline(UUID id, UUID repository, String name) {
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                    criticality, monitoring_status, created_at)
                VALUES (?, ?, 'shared-external-workflow', ?, '.github/workflows/build.yml', 'HIGH', 'PAUSED', ?)
                """, id, repository, name, Timestamp.from(observed));
    }

    private void insertExecution(UUID id, UUID pipeline, String externalId, boolean failed) {
        jdbc.update("""
                INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha,
                    external_status, external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, ?, 2, 'feature/operational-core', 'source-commit', 'completed', ?, ?, ?, ?, 'execution-correlation', ?)
                """, id, pipeline, externalId, failed ? "failure" : "success", failed,
                Timestamp.from(observed), Timestamp.from(observed.plusSeconds(15)), Timestamp.from(observed));
    }

    private void insertIncident(UUID id) {
        jdbc.update("""
                INSERT INTO ops.incidents (id, pipeline_id, status, stage, summary, detected_at, correlation_id, created_at)
                VALUES (?, ?, 'OPEN', 'TESTING', 'Sanitized direct SQL fixture', ?, 'direct-correlation', ?)
                """, id, pipelineId, Timestamp.from(observed), Timestamp.from(observed));
    }

    private void insertRun(UUID incident, UUID execution, String role) {
        jdbc.update("""
                INSERT INTO ops.incident_runs (id, incident_id, execution_id, role, correlation_id, created_at)
                VALUES (?, ?, ?, ?::ops."IncidentRunRole", 'run-correlation', ?)
                """, UUID.randomUUID(), incident, execution, role, Timestamp.from(observed));
    }

    private PSQLException postgresCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres) {
                return postgres;
            }
        }
        throw new AssertionError("Expected a PostgreSQL failure", error);
    }
}
