package org.pipesor.pipeline;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
@AutoConfigureRestTestClient
@Import(PostgresTestConfiguration.class)
public abstract class PostgresIntegrationTest {
}
