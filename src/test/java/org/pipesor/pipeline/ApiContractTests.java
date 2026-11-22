package org.pipesor.pipeline;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.security.TestIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(GitHubConnectorTestConfiguration.class)
class ApiContractTests extends PostgresIntegrationTest {
    static final String PRINCIPAL = TestIdentity.actor(TestIdentity.GITHUB_USER);
    @Autowired IncidentCommandService incidents;
    @Autowired PlatformTransactionManager manager;
    @Autowired GitHubConnectorDouble github;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestTestClient client;
    private UUID repository;
    private UUID visible;
    private UUID hidden;

    @BeforeEach
    void seed() {
        github.reset();
        repository = UUID.randomUUID(); visible = UUID.randomUUID(); hidden = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, 'http/service', now())", repository, repository.toString());
        github.allow(PRINCIPAL, repository.toString(), true);
        for (UUID id : new UUID[]{hidden, visible}) {
            jdbc.update("""
                    INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                            criticality, monitoring_status, created_at)
                    VALUES (?, ?, ?, 'HTTP workflow', 'workflow.yml', 'HIGH', 'ACTIVE', ?)
                    """, id, repository, id.toString(), Timestamp.from(Instant.parse(id.equals(hidden) ? "2026-11-06T00:00:00Z" : "2026-11-06T01:00:00Z")));
        }
        jdbc.update("""
                INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                VALUES (?, ?, ?, 'VIEW', 'http-fixture', now())
                """, UUID.randomUUID(), visible, PRINCIPAL);
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM audit.audit_events WHERE entity_id IN (?, ?) OR entity_id IN (SELECT id::text FROM ops.incidents WHERE pipeline_id IN (?, ?))", visible.toString(), hidden.toString(), visible, hidden);
            jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id IN (?, ?))", visible, hidden);
            jdbc.update("DELETE FROM ops.incidents WHERE pipeline_id IN (?, ?)", visible, hidden);
            jdbc.update("DELETE FROM ops.execution_stages WHERE execution_id IN (SELECT id FROM ops.pipeline_executions WHERE pipeline_id IN (?, ?))", visible, hidden);
            jdbc.update("DELETE FROM ops.pipeline_executions WHERE pipeline_id IN (?, ?)", visible, hidden);
            jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id IN (?, ?)", visible, hidden);
            jdbc.update("DELETE FROM ops.pipelines WHERE repository_id = ?", repository);
            jdbc.update("DELETE FROM ops.repositories WHERE id = ?", repository);
        });
    }

    @Test
    void acceptsOnlyPlatformSessionsWithThePipelineScope() throws Exception {
        client.get().uri("/api/v1/pipelines").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueMatches("Cache-Control", ".*no-store.*");
        long user = TestIdentity.GITHUB_USER;
        String[] invalid = {
            TestIdentity.forgedSession(user),
            TestIdentity.session(user, claims -> claims.issuer("urn:pipesor:other")),
            TestIdentity.session(user, claims -> claims.audience(List.of("urn:pipesor:api:ingestion"))),
            TestIdentity.session(user, claims -> claims.claim("tid", UUID.randomUUID().toString())),
            TestIdentity.session(user, claims -> claims.expirationTime(java.util.Date.from(Instant.now().minusSeconds(120)))),
            TestIdentity.session(user, claims -> claims.claim("oid", UUID.randomUUID().toString())),
            TestIdentity.session(user, claims -> claims.claim("azp", UUID.randomUUID().toString())),
            "not-a-jwt"
        };
        for (String invalidToken : invalid) {
            client.get().uri("/api/v1/pipelines").header("Authorization", "Bearer " + invalidToken)
                    .exchange().expectStatus().isUnauthorized().expectHeader().contentTypeCompatibleWith("application/problem+json")
                    .expectBody().jsonPath("$.correlationId").isNotEmpty().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        }
        // A valid workload token, or a session without Pipeline.Access, is authenticated but forbidden.
        client.get().uri("/api/v1/pipelines").header("Authorization", "Bearer "
                        + TestIdentity.workload("correlation", "Correlation.OperationalReferences.Check"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/pipelines").header("Authorization", "Bearer "
                        + TestIdentity.session(user, claims -> claims.claim("scp", "Diagnosis.Read")))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        // A subject token is never accepted next to a human session.
        client.get().uri("/api/v1/pipelines").header("Authorization", bearer())
                .header("X-Pipesor-Subject-Token", TestIdentity.session(user)).exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/pipelines").header("Authorization", bearer()).exchange().expectStatus().isOk()
                .expectHeader().valueMatches("Cache-Control", ".*no-store.*");
    }

    @Test
    void publishesOnlyHealthAndPublicKeysWithoutAuthentication() {
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.keys[0].kty").isEqualTo("RSA").jsonPath("$.keys[0].d").doesNotExist();
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void filtersBeforePaginationAndDeniesOtherPipelinesWithoutLeakingRows() throws Exception {
        client.get().uri("/api/v1/pipelines?offset=0&limit=1").header("Authorization", bearer())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(visible.toString());
        client.get().uri("/api/v1/pipelines?offset=1&limit=1").header("Authorization", bearer())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();
        client.get().uri("/api/v1/pipelines/" + hidden).header("Authorization", bearer())
                .header("X-Correlation-ID", "http-denied").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.correlationId").isEqualTo("http-denied");
        client.get().uri("/api/v1/pipelines?limit=101").header("Authorization", bearer()).exchange().expectStatus().isBadRequest();
    }

    @Test
    void derivesActorFromTokenAndCommitsOnlyWithConfigurePermission() throws Exception {
        var body = Map.of("criticality", "LOW", "monitoringStatus", "PAUSED", "environment", "local");
        client.put().uri("/api/v1/pipelines/" + visible + "/settings").header("Authorization", bearer())
                .header("X-Actor-ID", "configurer").body(body).exchange().expectStatus().isForbidden();
        jdbc.update("UPDATE ops.pipeline_access_grants SET capability = 'CONFIGURE' WHERE pipeline_id = ?", visible);
        client.put().uri("/api/v1/pipelines/" + visible + "/settings").header("Authorization", bearer())
                .header("X-Correlation-ID", "http-configure").body(body).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.criticality").isEqualTo("LOW");
        assertThat(jdbc.queryForObject("SELECT actor_ref FROM audit.audit_events WHERE entity_id = ?", String.class, visible.toString())).isEqualTo(PRINCIPAL);
        client.put().uri("/api/v1/pipelines/" + visible + "/settings").header("Authorization", bearer())
                .body(Map.of("environment", "invalid")).exchange().expectStatus().isBadRequest();
    }

    @Test
    void runsTheHumanLifecycleThroughAuthenticatedHttpAndPreservesConflictSemantics() throws Exception {
        UUID failed = UUID.randomUUID(); UUID verification = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha,
                    external_status, external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, 'http-origin', 1, 'main', 'sha', 'completed', 'failure', true, now(), now(), 'http-origin', now())
                """, failed, visible);
        var incident = incidents.handle(new OpenIncidentCommand(
                visible, failed, PipelineStage.TESTING,
                "Sanitized HTTP incident", Instant.now(), "http-incident"));
        jdbc.update("UPDATE ops.pipeline_access_grants SET capability = 'OPERATE' WHERE pipeline_id = ?", visible);
        var path = "/api/v1/incidents/" + incident.getId();
        client.post().uri(path + "/remediation").header("Authorization", bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("IN_REMEDIATION");
        var intervention = jdbc.queryForObject("SELECT remediation_started_at FROM ops.incidents WHERE id = ?", Timestamp.class, incident.getId()).toInstant();
        jdbc.update("""
                INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha,
                    external_status, external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, 'http-verification', 1, 'main', 'sha', 'completed', 'success', false, ?, now(), 'http-verification', now())
                """, verification, visible, Timestamp.from(intervention.plusNanos(1000)));
        client.post().uri(path + "/verification").header("Authorization", bearer())
                .body(Map.of("executionId", verification.toString())).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("VERIFICATION");
        client.post().uri(path + "/resolve").header("Authorization", bearer())
                .body(Map.of("executionId", failed.toString())).exchange().expectStatus().isEqualTo(409);
        client.post().uri(path + "/resolve").header("Authorization", bearer())
                .body(Map.of("executionId", verification.toString())).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("RESOLVED");
        client.post().uri(path + "/remediation").header("Authorization", bearer()).exchange().expectStatus().isEqualTo(409);
        client.get().uri(path + "/runs").header("Authorization", bearer()).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT actor_ref FROM audit.audit_events WHERE entity_id = ?", String.class,
                incident.getId().toString())).containsExactly(PRINCIPAL, PRINCIPAL, PRINCIPAL);
    }

    static String bearer() {
        return "Bearer " + TestIdentity.session(TestIdentity.GITHUB_USER);
    }

    static String bearerFor(long githubUserId) {
        return "Bearer " + TestIdentity.session(githubUserId);
    }
}
