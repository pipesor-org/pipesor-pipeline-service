package org.pipesor.pipeline;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

@ActiveProfiles("local")
class LocalOpenApiTests extends PostgresIntegrationTest {

    @Test
    void documentsImplementedBusinessEndpointsAndBearerSecurity(@Autowired RestTestClient client) {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isNotEmpty()
                .jsonPath("$.paths['/api/v1/pipelines'].get").exists()
                .jsonPath("$.paths['/api/v1/incidents/{id}/resolve'].post.security[0].bearerAuth").isArray()
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer")
                .jsonPath("$.paths['/api/v1/pipelines'].post.responses['201']").exists()
                .jsonPath("$.paths['/api/v1/pipelines'].post.responses['201'].headers.Location.schema.type").isEqualTo("string")
                .jsonPath("$.paths['/api/v1/pipelines/{id}/policy'].put").exists()
                .jsonPath("$.paths['/api/v1/pipelines/{id}/ownership'].put").exists()
                .jsonPath("$.paths['/api/v1/pipelines/{id}/grants'].get").exists()
                .jsonPath("$.paths['/api/v1/pipelines/{id}/activities'].get").exists()
                .jsonPath("$.paths['/api/v1/pipelines'].get.parameters[?(@.name == 'offset')].schema.type").isEqualTo(List.of("integer"))
                .jsonPath("$.paths['/api/v1/pipelines'].get.parameters[?(@.name == 'limit')].schema.type").isEqualTo(List.of("integer"))
                .jsonPath("$.paths['/api/v1/pipelines'].get.parameters[?(@.name == 'limit')].schema.maximum").isEqualTo(List.of(100))
                .jsonPath("$.paths['/api/v1/pipelines'].get.parameters[?(@.name == 'offset')].schema.minimum").isEqualTo(List.of(0))
                .jsonPath("$.paths['/api/v1/pipelines'].get.parameters[?(@.name == 'offset')].schema.default").isEqualTo(List.of(0))
                .jsonPath("$.paths['/api/v1/pipelines'].get.parameters[?(@.name == 'limit')].schema.default").isEqualTo(List.of(25));
    }

    @Test
    void servesSwaggerUiLocally(@Autowired RestTestClient client) {
        client.get().uri("/swagger-ui/index.html").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/html");
    }
}
