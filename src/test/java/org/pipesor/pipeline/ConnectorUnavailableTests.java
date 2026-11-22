package org.pipesor.pipeline;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Real Ingestion connector composition with Ingestion unreachable: human routes fail closed with 503. */
class ConnectorUnavailableTests extends PostgresIntegrationTest {
    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;

    @Test
    void authenticatedRequestsFailClosedWhenIngestionIsUnavailable() {
        var repository = UUID.randomUUID();
        var pipeline = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, '824111', 'down/service', now())", repository);
        jdbc.update("""
                INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                        criticality, monitoring_status, created_at)
                VALUES (?, ?, '919111', 'Down', 'down.yml', 'HIGH', 'ACTIVE', now())
                """, pipeline, repository);
        jdbc.update("""
                INSERT INTO ops.pipeline_access_grants (id, pipeline_id, principal_ref, capability, correlation_id, created_at)
                VALUES (?, ?, ?, 'VIEW', 'down-fixture', now())
                """, UUID.randomUUID(), pipeline, ApiContractTests.PRINCIPAL);
        try {
            client.get().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).exchange()
                    .expectStatus().isEqualTo(503).expectBody().jsonPath("$.correlationId").isNotEmpty()
                    .jsonPath("$.code").isEqualTo("GITHUB_VERIFICATION_UNAVAILABLE");
            client.get().uri("/api/v1/pipelines/" + pipeline).header("Authorization", ApiContractTests.bearer()).exchange()
                    .expectStatus().isEqualTo(503);
            client.get().uri("/api/v1/incidents?state=OPEN").header("Authorization", ApiContractTests.bearer()).exchange()
                    .expectStatus().isEqualTo(503);
            client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer())
                    .body(Map.of("githubRepositoryId", "823001", "githubWorkflowId", "918001", "criticality", "HIGH", "monitoringStatus", "ACTIVE"))
                    .exchange().expectStatus().isEqualTo(503);
            // Without any granted pipeline nothing is disclosed, so the empty list needs no GitHub verification.
            client.get().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearerFor(3003)).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();
        } finally {
            jdbc.update("DELETE FROM ops.pipeline_access_grants WHERE pipeline_id = ?", pipeline);
            jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", pipeline);
            jdbc.update("DELETE FROM ops.repositories WHERE id = ?", repository);
        }
    }
}
