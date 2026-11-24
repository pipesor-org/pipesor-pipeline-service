package org.pipesor.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.security.TestIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-pipeline queue (filtered before paging) and Recommendation's diagnosis read entitlement. */
@Import(GitHubConnectorTestConfiguration.class)
class IncidentQueueAndEntitlementTests extends PostgresIntegrationTest {
    private static final String ACTOR = ApiContractTests.PRINCIPAL;
    private static final String ENTITLEMENTS = "/internal/v1/diagnosis-read-entitlements";
    private static final String ROLE = "Recommendation.DiagnosisEntitlements.Resolve";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubConnectorDouble github;
    @Autowired IncidentCommandService incidents;
    @Autowired PlatformTransactionManager manager;
    private final List<UUID> repositories = new ArrayList<>();
    private final List<UUID> pipelines = new ArrayList<>();
    private UUID visible;
    private UUID denied;
    private UUID ungranted;
    private final List<UUID> visibleIncidents = new ArrayList<>();
    private UUID verificationIncident;
    private UUID deniedIncident;
    private UUID ungrantedIncident;
    private final Map<UUID, UUID> origins = new java.util.HashMap<>();

    @BeforeEach
    void seed() {
        github.reset();
        visible = pipeline("825001", true);
        denied = pipeline("825002", true);
        ungranted = pipeline("825003", false);
        github.facts(ACTOR, "825001", new GitHubConnector.RepositoryAccess("825001", "5001", "7001", true, true, false));
        github.facts(ACTOR, "825002", new GitHubConnector.RepositoryAccess("825002", "5001", "7001", true, false, false));
        github.facts(ACTOR, "825003", new GitHubConnector.RepositoryAccess("825003", "5001", "7001", true, true, false));
        for (int i = 0; i < 3; i++) visibleIncidents.add(incident(visible, i));
        verificationIncident = incident(visible, 3);
        jdbc.update("UPDATE ops.incidents SET status = 'VERIFICATION' WHERE id = ?", verificationIncident);
        deniedIncident = incident(denied, 4);
        ungrantedIncident = incident(ungranted, 5);
    }

    @AfterEach
    void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            for (UUID id : pipelines) {
                jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id IN (SELECT id FROM ops.incidents WHERE pipeline_id = ?)", id);
                jdbc.update("DELETE FROM ops.incidents WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipeline_executions WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id = ?", id);
                jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", id);
            }
            for (UUID id : repositories) jdbc.update("DELETE FROM ops.repositories WHERE id = ?", id);
        });
    }

    @Test
    void queueFiltersByGrantAndCurrentGitHubAccessBeforePaging() {
        var first = JSON.readTree(client.get().uri("/api/v1/incidents?state=OPEN&pageSize=3").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*")
                .expectBody().returnResult().getResponseBodyContent());
        // OPEN is every active incident, including the one in verification, newest first.
        assertThat(first.get("items")).hasSize(3);
        assertThat(first.get("items").get(0).get("id").asString()).isEqualTo(verificationIncident.toString());
        assertThat(first.get("items").get(1).get("id").asString()).isEqualTo(visibleIncidents.get(2).toString());
        assertThat(first.get("items").get(0).get("pipelineId").asString()).isEqualTo(visible.toString());
        // Display numbers come from the database sequence in creation order and never repeat.
        assertThat(first.get("items").get(0).get("displayNumber").asLong())
                .isGreaterThan(first.get("items").get(1).get("displayNumber").asLong()).isGreaterThan(1000L);
        String token = first.get("nextPageToken").asString();
        var second = JSON.readTree(client.get().uri("/api/v1/incidents?state=OPEN&pageSize=3&pageToken=" + token)
                .header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent());
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("id").asString()).isEqualTo(visibleIncidents.get(0).toString());
        assertThat(second.get("nextPageToken").isNull()).isTrue();

        // Diagnosis validation is owned by Recommendation, so Pipeline has no such lifecycle queue.
        client.get().uri("/api/v1/incidents?state=NEEDS_VALIDATION").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/incidents?state=RESOLVED").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();
        client.get().uri("/api/v1/incidents?state=OPEN&pageToken=bogus").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_PAGE_TOKEN");
        client.get().uri("/api/v1/incidents?state=OPEN&pageSize=101").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/incidents").header("Authorization", ApiContractTests.bearer()).exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/incidents?state=OPEN").exchange().expectStatus().isUnauthorized();
        github.unavailable = true;
        client.get().uri("/api/v1/incidents?state=OPEN").header("Authorization", ApiContractTests.bearer())
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void entitlementConfirmsViewAccessAndReturnsTheOriginScope() {
        var incident = visibleIncidents.getFirst();
        entitle(incident, TestIdentity.workload("recommendation", ROLE), TestIdentity.session(TestIdentity.GITHUB_USER))
                .expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*").expectBody()
                .jsonPath("$.incidentId").isEqualTo(incident.toString())
                .jsonPath("$.pipelineId").isEqualTo(visible.toString())
                .jsonPath("$.executionId").isEqualTo(origins.get(incident).toString())
                .jsonPath("$.tenantId").isEqualTo(TestIdentity.TENANT.toString())
                .jsonPath("$.githubRepositoryId").isEqualTo("825001")
                .jsonPath("$.githubInstallationId").isEqualTo("5001")
                .jsonPath("$.capabilities").isEqualTo(List.of("VIEW"))
                .jsonPath("$.githubAccountId").doesNotExist();
        jdbc.update("""
                INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                VALUES (?, ?, ?, 'OPERATE', 'queue-fixture', now())
                """, UUID.randomUUID(), visible, ACTOR);
        entitle(incident, TestIdentity.workload("recommendation", ROLE), TestIdentity.session(TestIdentity.GITHUB_USER))
                .expectStatus().isOk().expectBody().jsonPath("$.capabilities").isEqualTo(List.of("VIEW", "OPERATE"));
    }

    @Test
    void entitlementRequiresRecommendationWithAVerifiedSubject() {
        var incident = visibleIncidents.getFirst();
        String recommendation = TestIdentity.workload("recommendation", ROLE);
        String subject = TestIdentity.session(TestIdentity.GITHUB_USER);
        entitle(incident, recommendation, null).expectStatus().isForbidden();
        entitle(incident, recommendation, TestIdentity.forgedSession(TestIdentity.GITHUB_USER)).expectStatus().isUnauthorized();
        entitle(incident, recommendation, recommendation).expectStatus().isUnauthorized();
        entitle(incident, subject, subject).expectStatus().isUnauthorized();
        entitle(incident, subject, null).expectStatus().isForbidden();
        entitle(incident, TestIdentity.workload("correlation", "Correlation.OperationalReferences.Check"), subject)
                .expectStatus().isForbidden();
        // Missing VIEW is indistinguishable from absence.
        entitle(deniedIncident, recommendation, subject).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("INCIDENT_NOT_FOUND");
        entitle(ungrantedIncident, recommendation, subject).expectStatus().isNotFound();
        entitle(UUID.randomUUID(), recommendation, subject).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("INCIDENT_NOT_FOUND");
        client.post().uri(ENTITLEMENTS).header("Authorization", "Bearer " + recommendation)
                .header("X-Pipesor-Subject-Token", subject).contentType(MediaType.APPLICATION_JSON)
                .body("{\"incidentId\":\"" + incident + "\",\"actor\":\"x\"}").exchange().expectStatus().isBadRequest();
        github.unavailable = true;
        entitle(incident, recommendation, subject).expectStatus().isEqualTo(503);
    }

    private RestTestClient.ResponseSpec entitle(UUID incident, String authorization, String subject) {
        var request = client.post().uri(ENTITLEMENTS).header("Authorization", "Bearer " + authorization)
                .contentType(MediaType.APPLICATION_JSON);
        if (subject != null) request.header("X-Pipesor-Subject-Token", subject);
        return request.body(Map.of("incidentId", incident.toString())).exchange();
    }

    private UUID pipeline(String githubRepository, boolean grant) {
        var repository = UUID.randomUUID();
        var pipeline = UUID.randomUUID();
        repositories.add(repository);
        pipelines.add(pipeline);
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, 'queue/service', now())",
                repository, githubRepository);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                        criticality, monitoring_status, created_at)
                VALUES (?, ?, '926001', 'Queue workflow', 'queue.yml', 'HIGH', 'ACTIVE', now())
                """, pipeline, repository);
        if (grant) {
            jdbc.update("""
                    INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                    VALUES (?, ?, ?, 'VIEW', 'queue-fixture', now())
                    """, UUID.randomUUID(), pipeline, ACTOR);
        }
        return pipeline;
    }

    private UUID incident(UUID pipeline, int order) {
        var execution = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ops.pipeline_executions (id, pipeline_id, github_run_id, run_attempt, branch, commit_sha,
                    external_status, external_conclusion, failed, started_at, completed_at, correlation_id, created_at)
                VALUES (?, ?, ?, 1, 'main', 'sha', 'completed', 'failure', true, now(), now(), 'queue-origin', now())
                """, execution, pipeline, "run-" + order + "-" + execution);
        var opened = incidents.handle(new OpenIncidentCommand(pipeline, execution, PipelineStage.TESTING,
                "Queue incident " + order, Instant.now(), "queue-" + order));
        jdbc.update("UPDATE ops.incidents SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-11-20T00:00:00Z").plusSeconds(order)), opened.getId());
        origins.put(opened.getId(), execution);
        return opened.getId();
    }
}
