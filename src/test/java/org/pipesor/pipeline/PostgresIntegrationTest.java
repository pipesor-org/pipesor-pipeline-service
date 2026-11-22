package org.pipesor.pipeline;

import org.pipesor.pipeline.shared.security.TestIdentity;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the real application under the {@code local} profile against disposable PostgreSQL, with
 * test platform identities. Ingestion points at a closed loopback port unless a test overrides it;
 * the Service Bus receiver and the capture scope reconciler are disabled (consumer tests drive SDK
 * contexts directly; reconciliation is exercised without a schedule).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pipeline.messaging.enabled=false", "pipeline.ingestion.base-uri=http://127.0.0.1:9",
        "pipeline.capture-scope.reconcile-enabled=false"})
@ActiveProfiles("local")
@AutoConfigureRestTestClient
@Import({PostgresTestConfiguration.class, TestIdentity.Keys.class})
public abstract class PostgresIntegrationTest {
    @DynamicPropertySource
    static void platform(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://unused/overridden-by-service-connection");
    }
}
