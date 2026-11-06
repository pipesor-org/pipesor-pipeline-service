package org.pipesor.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

class PipelineServiceApplicationTests extends PostgresIntegrationTest {

    @Test
    void apiDocumentationIsDisabledByDefault(@Autowired RestTestClient client) {
        client.get().uri("/v3/api-docs").exchange().expectStatus().isNotFound();
    }

    @Test
    void swaggerUiIsDisabledByDefault(@Autowired RestTestClient client) {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isNotFound();
    }
}
