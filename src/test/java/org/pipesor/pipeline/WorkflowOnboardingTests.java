package org.pipesor.pipeline;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.pipesor.pipeline.catalog.application.commands.ConfigurePipelineCommand;
import org.pipesor.pipeline.catalog.application.commands.RegisterPipelineCommand;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.queries.PipelineActivityView;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.application.services.PipelineConfigurationService;
import org.pipesor.pipeline.catalog.application.services.PipelineRegistrationService;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;
import org.pipesor.pipeline.catalog.interfaces.rest.resources.PipelineActivitiesResource;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.security.TestIdentity;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL and HTTP are real; every GitHub fact comes from the explicitly configured test double. */
@Import(GitHubConnectorTestConfiguration.class)
class WorkflowOnboardingTests extends PostgresIntegrationTest {
    private static final String ACTOR = ApiContractTests.PRINCIPAL;
    private static final long OTHER_GITHUB_USER = 2002L;
    private static final String OTHER = TestIdentity.actor(OTHER_GITHUB_USER);
    private static final String REPO = "823001";
    private static final String SECOND_REPO = "823002";
    private static final String WORKFLOW = "918001";
    private static final String SECOND_WORKFLOW = "918002";
    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubConnectorDouble github;
    @Autowired PipelineRegistrationService registration;
    @Autowired PipelineConfigurationService configuration;
    @Autowired HumanPipelineAccess human;
    @Autowired IncidentCommandService incidents;
    @Autowired PlatformTransactionManager manager;

    @BeforeEach
    void explicitVerifiedSelections() {
        github.reset();
        for (String actor : List.of(ACTOR, OTHER)) {
            for (String repository : List.of(REPO, SECOND_REPO)) {
                github.allow(actor, repository, true);
                github.workflow(actor, repository, WORKFLOW, "verified/service-" + repository, "trunk", "Verified build", ".github/workflows/build.yml");
                github.workflow(actor, repository, SECOND_WORKFLOW, "verified/service-" + repository, "trunk", "Verified deploy", ".github/workflows/deploy.yml");
            }
        }
    }

    @AfterEach
    void cleanupOnlyOwnedFixtures() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var ids = jdbc.queryForList("SELECT p.id FROM ops.pipelines p JOIN ops.repositories r ON p.repository_id = r.id WHERE r.github_repository_id IN (?, ?)", UUID.class, REPO, SECOND_REPO);
            for (UUID id : ids) {
                jdbc.update("DELETE FROM audit.audit_events WHERE entity_id = ? OR entity_id IN (SELECT id::text FROM ops.incidents WHERE pipeline_id = ?)", id.toString(), id);
                jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id = ?)", id);
                jdbc.update("DELETE FROM ops.incidents WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.execution_stages WHERE execution_id IN (SELECT id FROM ops.pipeline_executions WHERE pipeline_id = ?)", id);
                jdbc.update("DELETE FROM ops.pipeline_executions WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", id);
            }
            jdbc.update("DELETE FROM ops.repositories WHERE github_repository_id IN (?, ?)", REPO, SECOND_REPO);
        });
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void registerListGetAndIndependentUpdatesUseVerifiedMetadataAndEffectiveCapabilities() throws Exception {
        var input = new HashMap<>(body(REPO, WORKFLOW));
        input.put("workflowName", "Untrusted client name"); input.put("workflowFile", "fake/path");
        input.put("fullName", "fake/repository"); input.put("actorRef", OTHER);
        var created = client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer())
                .body(input).exchange().expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/pipelines/" + DeterministicIds.pipeline(REPO, WORKFLOW))
                .expectBody(PipelineView.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.githubRepositoryId()).isEqualTo(REPO);
        assertThat(created.fullName()).isEqualTo("verified/service-" + REPO);
        assertThat(created.defaultBranch()).isEqualTo("trunk");
        assertThat(created.workflowName()).isEqualTo("Verified build");
        assertThat(created.workflowFile()).isEqualTo(".github/workflows/build.yml");
        assertThat(created.effectiveCapabilities()).containsExactlyInAnyOrder(AccessCapability.CONFIGURE, AccessCapability.VIEW);
        assertThat(jdbc.queryForList("SELECT principal_ref FROM ops.pipeline_access_grants WHERE pipeline_id = ?", String.class, created.id())).containsExactly(ACTOR);
        var path = "/api/v1/pipelines/" + created.id();
        assertThat(get(created.id())).isEqualTo(created);
        client.get().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].repositoryId").isEqualTo(created.repositoryId().toString())
                .jsonPath("$.items[0].fullName").isEqualTo(created.fullName());
        var policy = client.put().uri(path + "/policy").header("Authorization", ApiContractTests.bearer())
                .body(policyBody("prod")).exchange().expectStatus().isOk().expectBody(PipelineView.class).returnResult().getResponseBody();
        assertThat(policy.responsibleTeamRef()).isEqualTo("team-original");
        assertThat(policy.criticality()).isEqualTo(PipelineCriticality.CRITICAL);
        var ownership = client.put().uri(path + "/ownership").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("responsibleTeamRef", "team-platform", "responsibleTeamNameSnapshot", "Platform"))
                .exchange().expectStatus().isOk().expectBody(PipelineView.class).returnResult().getResponseBody();
        assertThat(ownership.environment()).isEqualTo("prod");
        assertThat(ownership.criticality()).isEqualTo(PipelineCriticality.CRITICAL);
        assertThat(ownership.monitoringStatus()).isEqualTo(MonitoringStatus.PAUSED);
        assertThat(get(created.id())).isEqualTo(ownership);
        assertThat(ownership).usingRecursiveComparison().ignoringFields("criticality", "monitoringStatus", "environment",
                "responsibleTeamRef", "responsibleTeamNameSnapshot").isEqualTo(created);
        client.put().uri(path + "/ownership").header("Authorization", ApiContractTests.bearer())
                .contentType(MediaType.APPLICATION_JSON).body("{\"responsibleTeamRef\":null,\"responsibleTeamNameSnapshot\":null}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.responsibleTeamRef").isEmpty();
        var audit = jdbc.queryForList("SELECT after_state->>'environment' AS environment, after_state->>'responsibleTeamRef' AS team FROM audit.audit_events WHERE entity_id = ? AND action = 'CONFIGURE_OWNERSHIP' ORDER BY occurred_at", created.id().toString());
        assertThat(audit.getFirst().get("environment")).isEqualTo("prod");
        assertThat(audit.getFirst().get("team")).isEqualTo("team-platform");
    }

    @Test
    void duplicateAndConcurrentRegistrationNeverReconfigureOrGrantTheLosingActor() throws Exception {
        var ready = new CountDownLatch(2);
        record Attempt(int status, String actor, String environment) {}
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Attempt> first = () -> {
                ready.countDown(); if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Missing competing registration"); }
                var input = new HashMap<>(body(REPO, WORKFLOW)); input.put("environment", "first");
                var result = client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(input).exchange().expectBody().returnResult();
                return new Attempt(result.getStatus().value(), ACTOR, "first");
            };
            Callable<Attempt> second = () -> {
                ready.countDown(); if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Missing competing registration"); }
                var input = new HashMap<>(body(REPO, WORKFLOW)); input.put("environment", "second");
                var result = client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).body(input).exchange().expectBody().returnResult();
                return new Attempt(result.getStatus().value(), OTHER, "second");
            };
            var firstFuture = workers.submit(first); var secondFuture = workers.submit(second);
            var outcomes = List.of(firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).extracting(Attempt::status).containsExactlyInAnyOrder(201, 409);
            var winner = outcomes.stream().filter(row -> row.status() == 201).findFirst().orElseThrow();
            UUID id = DeterministicIds.pipeline(REPO, WORKFLOW);
            assertThat(jdbc.queryForObject("SELECT environment FROM ops.pipelines WHERE id = ?", String.class, id)).isEqualTo(winner.environment());
            assertThat(jdbc.queryForList("SELECT principal_ref FROM ops.pipeline_access_grants WHERE pipeline_id = ?", String.class, id)).containsExactly(winner.actor());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events WHERE entity_id = ?", Integer.class, id.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories WHERE github_repository_id = ?", Integer.class, REPO)).isEqualTo(1);
        }
        var created = register(SECOND_REPO, SECOND_WORKFLOW);
        var changed = new HashMap<>(body(SECOND_REPO, SECOND_WORKFLOW)); changed.put("environment", "must-not-replace");
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).body(changed).exchange().expectStatus().isEqualTo(409);
        assertThat(get(created.id()).environment()).isEqualTo("dev");
        assertThat(jdbc.queryForList("SELECT principal_ref FROM ops.pipeline_access_grants WHERE pipeline_id = ?", String.class, created.id())).containsExactly(ACTOR);
    }

    @Test
    void requiresLinkedIdentityAdmittedInstallationReadAndAdministrationEvenWithAnotherConfigureGrant() throws Exception {
        register(SECOND_REPO, SECOND_WORKFLOW);
        github.unlink(ACTOR);
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(body(REPO, WORKFLOW))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("GITHUB_CONNECTION_REQUIRED");
        github.facts(ACTOR, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", true, false, false));
        rejectedRegistration(403);
        github.allow(ACTOR, REPO, false);
        rejectedRegistration(403);
        github.facts(ACTOR, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", false, true, true));
        rejectedRegistration(403);
        github.allow(ACTOR, REPO, true);
        github.unavailable = true;
        rejectedRegistration(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories WHERE github_repository_id = ?", Integer.class, REPO)).isZero();
    }

    @Test
    void rejectsMalformedIdsIncompatibleMetadataMisbindingAndExpiredProofWithoutWriting() throws Exception {
        var invalid = new HashMap<>(body("not-a-github-id", WORKFLOW));
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(invalid).exchange().expectStatus().isBadRequest();
        invalid = new HashMap<>(body(REPO, WORKFLOW)); invalid.remove("criticality");
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(invalid).exchange().expectStatus().isBadRequest();
        github.workflowOverride = facts -> new GitHubConnector.Workflow(facts.access(), SECOND_REPO, facts.workflowId(), facts.fullName(), facts.defaultBranch(), facts.workflowName(), facts.workflowFile());
        rejectedRegistration(503);
        github.workflowOverride = facts -> facts;
        github.accessOverride = facts -> new GitHubConnector.Access(OTHER, facts.subjectRef(), facts.operation(), facts.githubUserId(), facts.linked(), facts.verifiedAt(), facts.validUntil(), facts.repositories());
        rejectedRegistration(503);
        github.accessOverride = facts -> new GitHubConnector.Access(facts.actorRef(), OTHER, facts.operation(), facts.githubUserId(), facts.linked(), facts.verifiedAt(), facts.validUntil(), facts.repositories());
        rejectedRegistration(503);
        github.accessOverride = facts -> new GitHubConnector.Access(facts.actorRef(), facts.subjectRef(), GitHubConnector.Operation.VIEW, facts.githubUserId(), facts.linked(), facts.verifiedAt(), facts.validUntil(), facts.repositories());
        rejectedRegistration(503);
        github.accessOverride = facts -> new GitHubConnector.Access(facts.actorRef(), facts.subjectRef(), facts.operation(), facts.githubUserId(), facts.linked(), facts.verifiedAt(), facts.validUntil(),
                Map.of(REPO, new GitHubConnector.RepositoryAccess(SECOND_REPO, "5001", true, true, true)));
        rejectedRegistration(503);
        github.accessOverride = facts -> new GitHubConnector.Access(facts.actorRef(), facts.subjectRef(), facts.operation(), facts.githubUserId(), facts.linked(), facts.verifiedAt(), facts.validUntil(),
                Map.of(REPO, new GitHubConnector.RepositoryAccess(REPO, null, true, true, true)));
        rejectedRegistration(503);
        github.accessOverride = facts -> new GitHubConnector.Access(facts.actorRef(), facts.subjectRef(), facts.operation(), facts.githubUserId(), facts.linked(), facts.verifiedAt(), facts.verifiedAt().minusSeconds(1), facts.repositories());
        rejectedRegistration(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories WHERE github_repository_id = ?", Integer.class, REPO)).isZero();
    }

    @Test
    void joinsGithubEligibilityAndGrantsBeforePagingAndReportsEffectiveCapabilitiesOnly() throws Exception {
        var hidden = register(REPO, WORKFLOW); var visible = register(SECOND_REPO, SECOND_WORKFLOW);
        jdbc.update("UPDATE ops.pipelines SET created_at = '2020-01-01T00:00:00Z' WHERE id = ?", hidden.id());
        github.facts(ACTOR, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", true, false, false));
        github.allow(ACTOR, SECOND_REPO, false);
        client.get().uri("/api/v1/pipelines?offset=0&limit=1").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].id").isEqualTo(visible.id().toString())
                .jsonPath("$.items[0].effectiveCapabilities[0]").isEqualTo("VIEW")
                .jsonPath("$.items[0].principalRef").doesNotExist();
        client.get().uri("/api/v1/pipelines?offset=1&limit=1").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();
        assertThat(get(visible.id()).effectiveCapabilities()).containsExactly(AccessCapability.VIEW);
        client.put().uri("/api/v1/pipelines/" + visible.id() + "/policy").header("Authorization", ApiContractTests.bearer()).body(policyBody("changed")).exchange().expectStatus().isForbidden();
        github.unavailable = true;
        client.get().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isEqualTo(503);
        jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE principal_ref = ?", ACTOR);
        client.get().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void revokedGithubAccessCannotBeBypassedThroughAnyChildOrHumanCommand() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        UUID execution = execution(pipeline.id(), true);
        var incident = incidents.handle(new OpenIncidentCommand(pipeline.id(), execution, PipelineStage.TESTING, "Sanitized fixture", Instant.now(), "adr020-origin"));
        grant(pipeline.id(), ACTOR, "OPERATE");
        github.facts(ACTOR, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", true, false, false));
        for (String path : List.of("/pipelines/" + pipeline.id(), "/pipelines/" + pipeline.id() + "/executions",
                "/executions/" + execution, "/executions/" + execution + "/stages", "/pipelines/" + pipeline.id() + "/incidents",
                "/incidents/" + incident.getId(), "/incidents/" + incident.getId() + "/runs")) {
            client.get().uri("/api/v1" + path).header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isForbidden();
        }
        for (String action : List.of("remediation", "verification", "retry-remediation", "resolve")) {
            client.post().uri("/api/v1/incidents/" + incident.getId() + "/" + action).header("Authorization", ApiContractTests.bearer())
                    .body(Map.of("executionId", execution.toString())).exchange().expectStatus().isForbidden();
        }
        client.put().uri("/api/v1/pipelines/" + pipeline.id() + "/settings").header("Authorization", ApiContractTests.bearer()).body(policyBody("forbidden")).exchange().expectStatus().isForbidden();
        assertThat(jdbc.queryForObject("SELECT status::text FROM ops.incidents WHERE id = ?", String.class, incident.getId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT monitoring_status::text FROM ops.pipelines WHERE id = ?", String.class, pipeline.id())).isEqualTo("ACTIVE");
    }

    @Test
    void verificationAlsoChecksTheOtherExecutionRepository() throws Exception {
        var first = register(REPO, WORKFLOW); var second = register(SECOND_REPO, SECOND_WORKFLOW);
        grant(first.id(), ACTOR, "OPERATE");
        UUID origin = execution(first.id(), true);
        var incident = incidents.handle(new OpenIncidentCommand(first.id(), origin, PipelineStage.TESTING, "Sanitized failure", Instant.now(), "adr020-cross"));
        client.post().uri("/api/v1/incidents/" + incident.getId() + "/remediation").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk();
        UUID verification = execution(second.id(), false);
        github.facts(ACTOR, SECOND_REPO, new GitHubConnector.RepositoryAccess(SECOND_REPO, "5001", true, false, false));
        client.post().uri("/api/v1/incidents/" + incident.getId() + "/verification").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("executionId", verification.toString())).exchange().expectStatus().isForbidden();
        assertThat(jdbc.queryForObject("SELECT status::text FROM ops.incidents WHERE id = ?", String.class, incident.getId())).isEqualTo("IN_REMEDIATION");
        github.allow(ACTOR, SECOND_REPO, true);
        client.post().uri("/api/v1/incidents/" + incident.getId() + "/verification").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("executionId", verification.toString())).exchange().expectStatus().isOk();
        github.facts(ACTOR, SECOND_REPO, new GitHubConnector.RepositoryAccess(SECOND_REPO, "5001", true, false, false));
        client.get().uri("/api/v1/incidents/" + incident.getId() + "/runs").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isForbidden();
        github.allow(ACTOR, SECOND_REPO, true);
        client.get().uri("/api/v1/incidents/" + incident.getId() + "/runs").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(2);
        github.accessOverride = proof -> proof.operation() == GitHubConnector.Operation.VIEW
                ? new GitHubConnector.Access(proof.actorRef(), proof.subjectRef(), proof.operation(), "2002", proof.linked(), proof.verifiedAt(), proof.validUntil(), proof.repositories()) : proof;
        client.post().uri("/api/v1/incidents/" + incident.getId() + "/resolve").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("executionId", verification.toString())).exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("GITHUB_CONNECTION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM ops.incidents WHERE id = ?", String.class, incident.getId())).isEqualTo("VERIFICATION");
    }

    @Test
    void grantsRequireCurrentRecipientEligibilityAndAdministrationForConfigure() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        var path = "/api/v1/pipelines/" + pipeline.id() + "/grants/" + OTHER + "/";
        github.unlink(OTHER);
        client.put().uri(path + "VIEW").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isForbidden();
        github.allow(OTHER, REPO, false);
        client.put().uri(path + "CONFIGURE").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isForbidden();
        github.facts(OTHER, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", false, true, true));
        client.put().uri(path + "OPERATE").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isForbidden();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_access_grants WHERE pipeline_id = ? AND principal_ref = ?", Integer.class, pipeline.id(), OTHER)).isZero();
        github.allow(OTHER, REPO, true);
        client.put().uri(path + "CONFIGURE").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isNoContent();
        github.allow(OTHER, REPO, false);
        client.put().uri("/api/v1/pipelines/" + pipeline.id() + "/policy").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).body(policyBody("denied")).exchange().expectStatus().isForbidden();
        github.unlink(OTHER);
        client.delete().uri(path + "CONFIGURE").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isNoContent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_access_grants WHERE pipeline_id = ? AND principal_ref = ?", Integer.class, pipeline.id(), OTHER)).isZero();
    }

    @Test
    void theLastConfigureGrantCannotBeRevokedEvenByItsHolder() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        var grants = "/api/v1/pipelines/" + pipeline.id() + "/grants/";
        client.delete().uri(grants + ACTOR + "/CONFIGURE").header("Authorization", ApiContractTests.bearer()).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("LAST_CONFIGURE_GRANT");
        client.put().uri(grants + OTHER + "/CONFIGURE").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isNoContent();
        // With two holders either may go; the remaining one is the last again.
        client.delete().uri(grants + ACTOR + "/CONFIGURE").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange()
                .expectStatus().isNoContent();
        client.delete().uri(grants + OTHER + "/CONFIGURE").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("LAST_CONFIGURE_GRANT");
        // Only CONFIGURE is guarded.
        client.put().uri(grants + ACTOR + "/VIEW").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isNoContent();
        client.delete().uri(grants + ACTOR + "/VIEW").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isNoContent();
        assertThat(jdbc.queryForList("SELECT principal_ref FROM ops.pipeline_access_grants WHERE pipeline_id = ? AND capability = 'CONFIGURE'",
                String.class, pipeline.id())).containsExactly(OTHER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events WHERE entity_id = ? AND action = 'REVOKE'",
                Integer.class, pipeline.id().toString())).isEqualTo(2);
    }

    @Test
    void administratorsRevokingEachOtherConcurrentlyLeaveOneConfigureGrant() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        var grants = "/api/v1/pipelines/" + pipeline.id() + "/grants/";
        client.put().uri(grants + OTHER + "/CONFIGURE").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isNoContent();
        var ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Integer> actorRevokesOther = () -> {
                ready.countDown(); if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Missing competing revocation"); }
                return client.delete().uri(grants + OTHER + "/CONFIGURE").header("Authorization", ApiContractTests.bearer())
                        .exchange().expectBody().returnResult().getStatus().value();
            };
            Callable<Integer> otherRevokesActor = () -> {
                ready.countDown(); if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Missing competing revocation"); }
                return client.delete().uri(grants + ACTOR + "/CONFIGURE").header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER))
                        .exchange().expectBody().returnResult().getStatus().value();
            };
            var first = workers.submit(actorRevokesOther); var second = workers.submit(otherRevokesActor);
            // The loser no longer holds CONFIGURE when its turn comes.
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(204, 403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_access_grants WHERE pipeline_id = ? AND capability = 'CONFIGURE'",
                Integer.class, pipeline.id())).isEqualTo(1);
    }

    @Test
    void sectionUpdatesAreConcurrentAndSettingsRetainsReplacementCompatibility() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        var path = "/api/v1/pipelines/" + pipeline.id();
        var ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var policy = workers.submit(() -> {
                ready.countDown(); if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("No competing section update"); }
                client.put().uri(path + "/policy").header("Authorization", ApiContractTests.bearer()).body(policyBody("prod"))
                        .exchange().expectStatus().isOk(); return true;
            });
            var ownership = workers.submit(() -> {
                ready.countDown(); if (!ready.await(5, TimeUnit.SECONDS)) { throw new AssertionError("No competing section update"); }
                client.put().uri(path + "/ownership").header("Authorization", ApiContractTests.bearer())
                        .body(Map.of("responsibleTeamRef", "concurrent-team", "responsibleTeamNameSnapshot", "Concurrent team"))
                        .exchange().expectStatus().isOk(); return true;
            });
            assertThat(policy.get(15, TimeUnit.SECONDS)).isTrue(); assertThat(ownership.get(15, TimeUnit.SECONDS)).isTrue();
        }
        var result = get(pipeline.id());
        assertThat(result.environment()).isEqualTo("prod");
        assertThat(result.criticality()).isEqualTo(PipelineCriticality.CRITICAL);
        assertThat(result.responsibleTeamRef()).isEqualTo("concurrent-team");
        assertThat(result.responsibleTeamNameSnapshot()).isEqualTo("Concurrent team");
        client.put().uri(path + "/policy").header("Authorization", ApiContractTests.bearer()).body(Map.of("criticality", "LOW", "monitoringStatus", "ACTIVE"))
                .exchange().expectStatus().isBadRequest();
        client.put().uri(path + "/ownership").header("Authorization", ApiContractTests.bearer()).body(Map.of("responsibleTeamRef", "incomplete"))
                .exchange().expectStatus().isBadRequest();
        var cleared = new HashMap<>(policyBody("unused")); cleared.put("environment", null);
        client.put().uri(path + "/policy").header("Authorization", ApiContractTests.bearer()).body(cleared).exchange().expectStatus().isOk();
        assertThat(get(pipeline.id()).environment()).isNull();
        client.put().uri(path + "/settings").header("Authorization", ApiContractTests.bearer()).body(Map.of("criticality", "LOW", "monitoringStatus", "ACTIVE"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.responsibleTeamRef").isEmpty().jsonPath("$.environment").isEmpty();
    }

    @Test
    void failedAuditRollsBackRegistrationAndHistoryDoesNotCopyCredentials() throws Exception {
        jdbc.execute("CREATE FUNCTION audit.test_adr020_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced audit failure' USING ERRCODE='23514'; END; $$");
        jdbc.execute("CREATE TRIGGER test_adr020_reject BEFORE INSERT ON audit.audit_events FOR EACH ROW EXECUTE FUNCTION audit.test_adr020_reject()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> registration.handle(command(REPO, WORKFLOW)));
            assertThat(postgres(failure).getSQLState()).isEqualTo("23514");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories WHERE github_repository_id = ?", Integer.class, REPO)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipelines WHERE id = ?", Integer.class, DeterministicIds.pipeline(REPO, WORKFLOW))).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_access_grants WHERE pipeline_id = ?", Integer.class, DeterministicIds.pipeline(REPO, WORKFLOW))).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER test_adr020_reject ON audit.audit_events"); jdbc.execute("DROP FUNCTION audit.test_adr020_reject()");
        }
        var pipeline = register(REPO, WORKFLOW);
        jdbc.update("UPDATE ops.pipelines SET environment = 'Bearer legacy-placeholder' WHERE id = ?", pipeline.id());
        configuration.handle(new ConfigurePipelineCommand(pipeline.id(), ACTOR, "adr020-redaction", null, null, null,
                "clean-team", "Clean team", ConfigurePipelineCommand.Section.OWNERSHIP));
        assertThat(jdbc.queryForObject("SELECT before_state->>'environment' FROM audit.audit_events WHERE correlation_id = 'adr020-redaction'", String.class)).isEqualTo("[REDACTED]");
        client.put().uri("/api/v1/pipelines/" + pipeline.id() + "/policy").header("Authorization", ApiContractTests.bearer())
                .body(policyBody("ghp_sensitive-placeholder")).exchange().expectStatus().isBadRequest();
    }

    @Test
    void proofReuseIsLimitedToOneRequestAndRevocationAppliesToTheNext() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        github.verifications.set(0);
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            human.require(ACTOR, pipeline.id(), AccessCapability.VIEW);
            human.require(ACTOR, pipeline.id(), AccessCapability.VIEW);
            assertThat(github.verifications.get()).isEqualTo(1);
            RequestContextHolder.resetRequestAttributes();
            github.facts(ACTOR, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", true, false, false));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            assertThrows(SecurityException.class, () -> human.require(ACTOR, pipeline.id(), AccessCapability.VIEW));
            assertThat(github.verifications.get()).isEqualTo(2);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @Test
    void reusesVerifiedRepositoryAndRejectsAnExistingWorkflowWithALegacyUuid() throws Exception {
        var first = register(REPO, WORKFLOW);
        github.workflow(ACTOR, REPO, SECOND_WORKFLOW, "renamed/service", null, "Verified deploy", ".github/workflows/deploy.yml");
        var second = register(REPO, SECOND_WORKFLOW);
        assertThat(second.repositoryId()).isEqualTo(first.repositoryId());
        assertThat(second.defaultBranch()).isNull();
        assertThat(get(first.id()).fullName()).isEqualTo("renamed/service");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.repositories WHERE github_repository_id = ?", Integer.class, REPO)).isEqualTo(1);
        UUID legacy = UUID.randomUUID();
        github.workflow(ACTOR, REPO, "918003", "not-applied/service", "main", "Legacy", "legacy.yml");
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file, criticality, monitoring_status, created_at)
                VALUES (?, ?, '918003', 'Original legacy', 'legacy.yml', 'LOW', 'PAUSED', now())
                """, legacy, first.repositoryId());
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(body(REPO, "918003"))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT id FROM ops.pipelines WHERE repository_id = ? AND github_workflow_id = '918003'", UUID.class, first.repositoryId())).isEqualTo(legacy);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.pipeline_access_grants WHERE pipeline_id = ?", Integer.class, legacy)).isZero();
        assertThat(get(first.id()).fullName()).isEqualTo("renamed/service");
    }

    @Test
    void administrationQueriesRequireConfigureAndExposeOnlySanitizedPartialPipelineHistory() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        var path = "/api/v1/pipelines/" + pipeline.id();
        client.get().uri(path + "/grants").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].principalRef").isEqualTo(ACTOR).jsonPath("$.items[0].capability").isEqualTo("CONFIGURE");
        grant(pipeline.id(), OTHER, "VIEW");
        for (String resource : List.of("grants", "activities")) {
            client.get().uri(path + "/" + resource).header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isForbidden();
        }
        jdbc.update("""
                INSERT INTO audit.audit_events (id, category, correlation_id, actor_ref, entity_type, entity_id, action,
                    before_state, after_state, occurred_at, created_at)
                VALUES (?, 'access_and_governance_events', 'adr020-old-history', ?, 'pipeline', ?, 'CONFIGURE',
                    '{"criticality":"LOW"}'::jsonb, '{"criticality":"HIGH","githubToken":"ghp_fixture"}'::jsonb, '2020-01-01T00:00:00Z', now()),
                    (?, 'human_review', 'adr020-other-owner', ?, 'diagnosis', ?, 'CONFIGURE', null, null, now(), now())
                """, UUID.randomUUID(), ACTOR, pipeline.id().toString(), UUID.randomUUID(), ACTOR, pipeline.id().toString());
        var history = client.get().uri(path + "/activities").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody(PipelineActivitiesResource.class).returnResult().getResponseBody();
        assertThat(history.historyComplete()).isFalse();
        assertThat(history.items()).extracting(PipelineActivityView::action).containsExactly("REGISTER", "CONFIGURE");
        assertThat(history.items().getLast().afterState()).containsEntry("criticality", "HIGH").doesNotContainKeys("environment", "responsibleTeamRef", "githubToken");
        client.get().uri(path + "/activities?offset=1&limit=1").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.items[0].action").isEqualTo("CONFIGURE");
        github.allow(ACTOR, REPO, false);
        for (String resource : List.of("grants", "activities")) {
            client.get().uri(path + "/" + resource).header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void readersWithoutGrantsLearnOnlyWhichWorkflowsAreMonitored() throws Exception {
        var build = register(REPO, WORKFLOW);
        var deploy = register(REPO, SECOND_WORKFLOW);
        var uri = "/api/v1/pipelines/registrations?repositoryId=" + REPO;
        github.allow(OTHER, REPO, false);
        client.get().uri(uri).header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.repositoryId").isEqualTo(REPO).jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].pipelineId").isEqualTo(build.id().toString()).jsonPath("$.items[0].githubWorkflowId").isEqualTo(WORKFLOW)
                .jsonPath("$.items[0].accessible").isEqualTo(false).jsonPath("$.items[0].workflowName").doesNotExist()
                .jsonPath("$.items[1].pipelineId").isEqualTo(deploy.id().toString()).jsonPath("$.items[1].accessible").isEqualTo(false);
        client.get().uri(uri).header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].accessible").isEqualTo(true).jsonPath("$.items[1].accessible").isEqualTo(true);
        client.get().uri("/api/v1/pipelines/registrations?repositoryId=" + SECOND_REPO).header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0);
        // A stored grant without current read access is not accessible, and without read access nothing is disclosed.
        grant(build.id(), OTHER, "VIEW");
        github.facts(OTHER, REPO, new GitHubConnector.RepositoryAccess(REPO, "5001", true, false, false));
        client.get().uri(uri).header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        github.unlink(OTHER);
        client.get().uri(uri).header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("GITHUB_CONNECTION_REQUIRED");
        for (var query : List.of("", "?repositoryId=abc", "?repositoryId=0", "?repositoryId=" + REPO + "&repositoryId=" + SECOND_REPO)) {
            client.get().uri("/api/v1/pipelines/registrations" + query).header("Authorization", ApiContractTests.bearer()).exchange()
                    .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
        }
        github.allow(OTHER, REPO, false);
        client.get().uri(uri).header("Authorization", ApiContractTests.bearerFor(OTHER_GITHUB_USER)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].accessible").isEqualTo(true).jsonPath("$.items[1].accessible").isEqualTo(false);
    }

    @Test
    void rechecksALocalGrantRevokedWhileTheConnectorIsVerifyingAccess() throws Exception {
        var pipeline = register(REPO, WORKFLOW);
        github.accessOverride = proof -> {
            jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id = ? AND principal_ref = ?", pipeline.id(), ACTOR);
            return proof;
        };
        client.get().uri("/api/v1/pipelines/" + pipeline.id()).header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isForbidden();
    }

    private Map<String, Object> body(String repository, String workflow) {
        return Map.of("githubRepositoryId", repository, "githubWorkflowId", workflow, "criticality", "HIGH", "monitoringStatus", "ACTIVE",
                "environment", "dev", "responsibleTeamRef", "team-original", "responsibleTeamNameSnapshot", "Original team");
    }
    private Map<String, Object> policyBody(String environment) {
        return Map.of("criticality", "CRITICAL", "monitoringStatus", "PAUSED", "environment", environment);
    }
    private RegisterPipelineCommand command(String repository, String workflow) {
        return new RegisterPipelineCommand(ACTOR, "adr020-register", repository, workflow, PipelineCriticality.HIGH, MonitoringStatus.ACTIVE,
                "dev", "team-original", "Original team");
    }
    private PipelineView register(String repository, String workflow) throws Exception {
        return client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(body(repository, workflow))
                .exchange().expectStatus().isCreated().expectBody(PipelineView.class).returnResult().getResponseBody();
    }
    private PipelineView get(UUID id) throws Exception {
        return client.get().uri("/api/v1/pipelines/" + id).header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody(PipelineView.class).returnResult().getResponseBody();
    }
    private void rejectedRegistration(int status) throws Exception {
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).body(body(REPO, WORKFLOW)).exchange().expectStatus().isEqualTo(status);
    }
    private void grant(UUID pipeline, String actor, String capability) {
        jdbc.update("""
                INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                VALUES (?, ?, ?, ?::ops."AccessCapability", 'adr020-fixture', now())
                """, UUID.randomUUID(), pipeline, actor, capability);
    }
    private UUID execution(UUID pipeline, boolean failed) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha,
                    external_status, external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, ?, 1, 'feature/not-default', 'sha', 'completed', ?, ?, now(), now(), 'adr020-execution', now())
                """, id, pipeline, id.toString(), failed ? "failure" : "success", failed);
        return id;
    }
    private PSQLException postgres(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException pg) { return pg; }
        }
        throw new AssertionError("Expected PostgreSQL audit rejection", error);
    }
}
