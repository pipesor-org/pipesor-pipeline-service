package org.pipesor.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

@ActiveProfiles("local")
class LocalOpenApiTests extends PostgresIntegrationTest {

    @Test
    void generatesOpenApiWithoutInventingBusinessEndpoints(@Autowired RestTestClient client) {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isNotEmpty()
                .jsonPath("$.paths").isEmpty();
    }

    @Test
    void servesSwaggerUiLocally(@Autowired RestTestClient client) {
        client.get().uri("/swagger-ui/index.html").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html");
    }
}
