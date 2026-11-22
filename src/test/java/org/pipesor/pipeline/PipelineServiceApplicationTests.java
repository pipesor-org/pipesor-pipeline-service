package org.pipesor.pipeline;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** OpenAPI and Swagger UI are enabled only by the local profile; the base configuration keeps them off. */
class PipelineServiceApplicationTests {
    @Test
    void apiDocumentationIsDisabledOutsideTheLocalProfile() throws IOException {
        var base = new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"));
        assertThat(base.getFirst().getProperty("springdoc.api-docs.enabled")).hasToString("false");
        assertThat(base.getFirst().getProperty("springdoc.swagger-ui.enabled")).hasToString("false");
        assertThat(base.getFirst().getProperty("spring.flyway.enabled")).hasToString("true");
        assertThat(base.getFirst().getProperty("spring.jpa.hibernate.ddl-auto")).hasToString("validate");
        assertThat(base.getFirst().getProperty("management.endpoints.web.exposure.include")).hasToString("health");
        var local = new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml"));
        assertThat(local.getFirst().getProperty("springdoc.api-docs.enabled")).hasToString("true");
    }
}
