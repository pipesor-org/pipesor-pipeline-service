package org.pipesor.pipeline;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.configuration.ApiSecurityConfiguration;
import org.pipesor.pipeline.incident.application.commands.OpenIncidentCommand;
import org.pipesor.pipeline.incident.application.services.IncidentCommandService;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import({ApiContractTests.SignedIdentityConfiguration.class, GitHubConnectorTestConfiguration.class})
class ApiContractTests extends PostgresIntegrationTest {
    private static final String TENANT = "61c97dae-b3ad-4cd8-9955-b39e21a2f80a";
    private static final String SUBJECT = "db155203-fcb8-4cb4-9597-c853f21b0235";
    static final String PRINCIPAL = TENANT + ":" + SUBJECT;
    private static final String ISSUER = "https://identity.example.test/tenant/v2.0";
    private static final KeyPair KEYS = keys();
    @Autowired IncidentCommandService incidents;
    @Autowired PlatformTransactionManager manager;
    @Autowired GitHubConnectorDouble github;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestTestClient client;
    private UUID repository;
    private UUID visible;
    private UUID hidden;

    @TestConfiguration(proxyBeanMethods = false)
    static class SignedIdentityConfiguration {
        @Bean @Primary
        JwtDecoder signedTestDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),
                    ApiSecurityConfiguration.identityValidator(TENANT, "pipeline-api", "Pipeline.Access")));
            return decoder;
        }
    }

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
    void verifiesSignaturesIssuerAudienceTenantLifetimeAndDelegatedScope() throws Exception {
        client.get().uri("/api/v1/pipelines").exchange().expectStatus().isUnauthorized();
        String[] invalid = {
            token(KEYS, ISSUER, "pipeline-api", TENANT, SUBJECT, "Pipeline.Access", null),
            token(keys(), ISSUER, "pipeline-api", TENANT, SUBJECT, "Pipeline.Access", Instant.now().plusSeconds(3600)),
            token(KEYS, "https://wrong.example.test", "pipeline-api", TENANT, SUBJECT, "Pipeline.Access", Instant.now().plusSeconds(3600)),
            token(KEYS, ISSUER, "wrong-api", TENANT, SUBJECT, "Pipeline.Access", Instant.now().plusSeconds(3600)),
            token(KEYS, ISSUER, "pipeline-api", UUID.randomUUID().toString(), SUBJECT, "Pipeline.Access", Instant.now().plusSeconds(3600)),
            token(KEYS, ISSUER, "pipeline-api", TENANT, SUBJECT, "Pipeline.Access", Instant.now().minusSeconds(3600)),
            token(KEYS, ISSUER, "pipeline-api", TENANT, "not-a-uuid", "Pipeline.Access", Instant.now().plusSeconds(3600)),
            token(KEYS, ISSUER, "pipeline-api", TENANT, SUBJECT, "Other.Scope", Instant.now().plusSeconds(3600))
        };
        for (String invalidToken : invalid) {
            client.get().uri("/api/v1/pipelines").header("Authorization", "Bearer " + invalidToken)
                    .exchange().expectStatus().isUnauthorized().expectHeader().contentTypeCompatibleWith("application/problem+json")
                    .expectBody().jsonPath("$.correlationId").isNotEmpty();
        }
        client.get().uri("/api/v1/pipelines").header("Authorization", bearer()).exchange().expectStatus().isOk();
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

    static String bearer() throws Exception {
        return "Bearer " + token(KEYS, ISSUER, "pipeline-api", TENANT, SUBJECT, "Pipeline.Access", Instant.now().plusSeconds(3600));
    }

    static String bearerFor(String subject) throws Exception {
        return "Bearer " + token(KEYS, ISSUER, "pipeline-api", TENANT, subject, "Pipeline.Access", Instant.now().plusSeconds(3600));
    }

    private static String token(KeyPair keys, String issuer, String audience, String tenant, String oid, String scope, Instant expires) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).claim("tid", tenant).claim("oid", oid).claim("scp", scope)
                .expirationTime(expires == null ? null : Date.from(expires)).build());
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
