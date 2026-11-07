package org.pipesor.pipeline;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Real default connector composition; only Entra JWT signing is local, with no GitHub double installed. */
@Import(ApiContractTests.SignedIdentityConfiguration.class)
class ConnectorUnavailableTests extends PostgresIntegrationTest {
    @Test
    void authenticatedRequestsFailClosedWithoutAnExecutableConnector(@Autowired RestTestClient client) throws Exception {
        client.get().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer()).exchange()
                .expectStatus().isEqualTo(503).expectBody().jsonPath("$.correlationId").isNotEmpty();
        client.post().uri("/api/v1/pipelines").header("Authorization", ApiContractTests.bearer())
                .body(Map.of("githubRepositoryId", "823001", "githubWorkflowId", "918001", "criticality", "HIGH", "monitoringStatus", "ACTIVE"))
                .exchange().expectStatus().isEqualTo(503);
    }
}
