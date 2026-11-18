package org.pipesor.pipeline;

import com.azure.core.credential.AccessToken;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.pipesor.pipeline.configuration.OwnerValidationConfiguration;
import org.pipesor.pipeline.configuration.OwnerValidationProperties;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.infrastructure.access.HttpIngestionScopeAuthority;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@Import(OwnerValidationHttpTests.LocalAuthority.class)
@TestPropertySource(properties = {
        "pipeline.owner-validation.enabled=true",
        "pipeline.owner-validation.issuer=https://identity.test/tenant/v2.0",
        "pipeline.owner-validation.jwk-set-uri=https://identity.test/keys",
        "pipeline.owner-validation.tenant=" + OwnerValidationHttpTests.DIRECTORY,
        "pipeline.owner-validation.audience=pipeline-machine",
        "pipeline.owner-validation.caller-client-id=" + OwnerValidationHttpTests.CLIENT,
        "pipeline.owner-validation.caller-principal-id=" + OwnerValidationHttpTests.PRINCIPAL,
        "pipeline.owner-validation.role=Evidence.Check",
        "pipeline.owner-validation.correlation-client-id=" + OwnerValidationHttpTests.CORRELATION_CLIENT,
        "pipeline.owner-validation.correlation-principal-id=" + OwnerValidationHttpTests.CORRELATION_PRINCIPAL,
        "pipeline.owner-validation.correlation-role=Correlation.OperationalReferences.Check",
        "pipeline.owner-validation.clock-skew=0s",
        "pipeline.owner-validation.request-timeout=3s",
        "pipeline.owner-validation.max-request-bytes=2048",
        "pipeline.owner-validation.max-response-bytes=1024",
        "pipeline.owner-validation.max-in-flight=1",
        "pipeline.owner-validation.ingestion-uri=https://ingestion.test/internal/v1/capture/machine-scope-checks",
        "pipeline.owner-validation.ingestion-scope=api://ingestion-machine/.default",
        "pipeline.owner-validation.connect-timeout=1s",
})
class OwnerValidationHttpTests extends PostgresIntegrationTest {
    static final String DIRECTORY = "31bb0907-a72d-422e-ad83-96355c46b2c3";
    static final String CLIENT = "4166972a-7490-433e-a5cf-3725d6ff823c";
    static final String PRINCIPAL = "08abff88-1291-47a2-b797-17b862032e95";
    static final String CORRELATION_CLIENT = "3086a1a3-f6ef-47ae-a049-cf9549c96f48";
    static final String CORRELATION_PRINCIPAL = "b624214f-7c7e-4688-b49a-53f1c7aeb346";
    private static final String CORRELATION_ROLE = "Correlation.OperationalReferences.Check";
    private static final String CAPTURE = "6557f111-9a27-40c6-8396-97a8ed967821";
    private static final String CORRELATION = "owner-validation-http";
    private static final String REPOSITORY = "912034000000000000001";
    private static final String WORKFLOW = "972345";
    private static final String RUN = "981234";
    private static final UUID PIPELINE = DeterministicIds.pipeline(REPOSITORY, WORKFLOW);
    private static final UUID EXECUTION = DeterministicIds.execution(REPOSITORY, RUN, 1);
    private static final UUID INCIDENT = DeterministicIds.incident(EXECUTION);
    private static final String PATH = "/internal/v1/evidence/operational-reference-checks";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final KeyPair KEYS = keys();

    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ExecutionSnapshotService snapshots;
    @Autowired ScopePeer scope;
    @LocalServerPort int port;

    @BeforeEach
    void resetPeer() {
        scope.calls.set(0);
        scope.tokenLifetime.set(Duration.ofMinutes(1));
    }

    @Test
    void releasesAdmissionAfterAChunkedBodyExceedsTheDeadline() throws Exception {
        var authorized = token(Map.of());
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(7000);
            String request = "POST " + PATH + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nAuthorization: Bearer " + authorized + "\r\n"
                    + "X-Correlation-Id: " + CORRELATION + "\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n1\r\n{\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reply = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(reply.readLine()).contains("503");
        }
        assertThat(scope.calls).hasValue(0);
        post(body(), authorized).expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("NOT_READY");
    }


    @TestConfiguration(proxyBeanMethods = false)
    static class LocalAuthority {
        @Bean @Primary
        OwnerValidationConfiguration.MachineDecoder signedMachine(OwnerValidationProperties settings) {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(OwnerValidationConfiguration.identityValidator(settings, Clock.systemUTC()));
            return new OwnerValidationConfiguration.MachineDecoder(decoder);
        }

        @Bean ScopePeer scopePeer() throws Exception { return new ScopePeer(); }

        @Bean @Primary
        HttpIngestionScopeAuthority localIngestion(ScopePeer scope) {
            return new HttpIngestionScopeAuthority(scope.client, scope.uri, remaining ->
                    new AccessToken("test-recipient-token", OffsetDateTime.now().plus(scope.tokenLifetime.get())), 1024, Clock.systemUTC());
        }
    }

    @Test
    void acceptsCorrelationOnlyWithItsWholeConfiguredCallerTuple() throws Exception {
        var correlation = Map.<String,Object>of("azp", CORRELATION_CLIENT, "oid", CORRELATION_PRINCIPAL,
                "roles", List.of(CORRELATION_ROLE));
        for (var crossed : List.of(
                Map.<String,Object>of("azp", CORRELATION_CLIENT),
                Map.<String,Object>of("oid", CORRELATION_PRINCIPAL),
                Map.<String,Object>of("roles", List.of(CORRELATION_ROLE)),
                Map.<String,Object>of("azp", CORRELATION_CLIENT, "oid", CORRELATION_PRINCIPAL))) {
            post(body(), token(crossed)).expectStatus().isForbidden();
        }
        assertThat(scope.calls).hasValue(0);
        post(body(), token(correlation)).expectStatus().isOk().expectBody()
                .jsonPath("$.decision").isEqualTo("NOT_READY");
        assertThat(scope.calls).hasValue(1);
        post(body(), token(Map.of())).expectStatus().isOk().expectBody()
                .jsonPath("$.decision").isEqualTo("NOT_READY");
        assertThat(scope.calls).hasValue(2);
    }

    @Test
    void checksSignedMachineIdentityThenCurrentScopeThenCommittedOrigin() throws Exception {
        var repository = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, 'owner/http', now())",
                repository, REPOSITORY);
        jdbc.update("""
            INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                criticality, monitoring_status, created_at) VALUES (?, ?, ?, 'Owner test', 'owner.yml', 'HIGH', 'ACTIVE', now())
            """, PIPELINE, repository, WORKFLOW);
        try {
            post(body(), null).expectStatus().isUnauthorized();
            post(body(), token(Map.of("aud", "wrong-service"))).expectStatus().isUnauthorized();
            post(body(), token(Map.of("scp", "Human.Access"))).expectStatus().isUnauthorized();
            post(body(), token(Map.of("oid", UUID.randomUUID().toString()))).expectStatus().isForbidden();
            post(body(), token(Map.of("roles", List.of("Wrong")))).expectStatus().isForbidden();
            assertThat(scope.calls).hasValue(0);
            var authorized = token(Map.of());
            var request = body();
            post(request, authorized).expectStatus().isOk().expectBody()
                    .jsonPath("$.decision").isEqualTo("NOT_READY")
                    .jsonPath("$.requestId").isEqualTo(request.get("requestId").asString());
            assertThat(scope.lastRequest.get()).isNotEqualTo(request.get("requestId").asString());

            var now = Instant.now();
            var snapshot = new ExecutionSnapshot("1.0", UUID.randomUUID(), "execution.snapshot", "github_actions",
                    now, now, CORRELATION, new ExecutionSnapshot.Pipeline(REPOSITORY, WORKFLOW),
                    new ExecutionSnapshot.Execution(RUN, 1, "main", "completed", "failure", now, now),
                    new ExecutionSnapshot.Commit("0123456789abcdef0123456789abcdef01234567"), null, null,
                    List.of(new ExecutionSnapshot.Stage(PipelineStage.TESTING, "completed", now, now)),
                    new ExecutionSnapshot.Failure(PipelineStage.TESTING, "Protected failure", now));
            assertThat(snapshots.handle(new ExecutionSnapshotTransformer().fromJson(JSON.writeValueAsString(snapshot))))
                    .isEqualTo("APPLIED");
            post(body(), authorized).expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("AUTHORIZED");
            post(body().put("incidentId", UUID.randomUUID().toString()), authorized)
                    .expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("MISMATCH");
            post(body().put("scopeRevision", "revoked"), authorized)
                    .expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("DENIED");
            post(body().put("scopeRevision", "mismatched-reply"), authorized).expectStatus().isEqualTo(503);
            post(body().put("deadlineAt", Instant.now().minusSeconds(1).toString()), authorized)
                    .expectStatus().isEqualTo(503);
            post(body().put("deadlineAt", "malformedZ"), authorized).expectStatus().isBadRequest();
            scope.tokenLifetime.set(Duration.ofSeconds(1));
            post(body().put("scopeRevision", "expired-response"), authorized).expectStatus().isEqualTo(503);
            assertThat(scope.calls).hasValue(6);
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("DELETE FROM ops.execution_event_receipts WHERE correlation_id = ?", CORRELATION);
                jdbc.update("DELETE FROM ops.incident_runs WHERE incident_id = ?", INCIDENT);
                jdbc.update("DELETE FROM ops.incidents WHERE id = ?", INCIDENT);
                jdbc.update("DELETE FROM ops.execution_stages WHERE execution_id = ?", EXECUTION);
                jdbc.update("DELETE FROM ops.pipeline_executions WHERE id = ?", EXECUTION);
                jdbc.update("DELETE FROM ops.pipelines WHERE id = ?", PIPELINE);
                jdbc.update("DELETE FROM ops.repositories WHERE id = ?", repository);
            });
        }
    }

    private RestTestClient.ResponseSpec post(ObjectNode body, String token) {
        var request = client.post().uri(PATH).header("X-Correlation-Id", CORRELATION)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.body(JSON.writeValueAsString(body)).exchange();
    }

    private static ObjectNode body() {
        return JSON.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("correlationId", CORRELATION)
                .put("deadlineAt", Instant.now().plusSeconds(10).toString()).put("tenantId", CAPTURE)
                .put("githubAccountId", "12").put("githubInstallationId", "34").put("githubRepositoryId", REPOSITORY)
                .put("githubWorkflowId", WORKFLOW).put("scopeRevision", "rev-1").put("githubRunId", RUN).put("runAttempt", 1)
                .put("pipelineId", PIPELINE.toString()).put("executionId", EXECUTION.toString()).put("incidentId", INCIDENT.toString());
    }

    private static String token(Map<String,Object> overrides) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://identity.test/tenant/v2.0").audience("pipeline-machine")
                .claim("tid", DIRECTORY).claim("idtyp", "app").claim("ver", "2.0").claim("azp", CLIENT)
                .claim("oid", PRINCIPAL).claim("roles", List.of("Evidence.Check"))
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(120)));
        overrides.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static final class ScopePeer implements AutoCloseable {
        final HttpsServer server;
        final HttpClient client;
        final URI uri;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastRequest = new AtomicReference<>();
        final AtomicReference<Duration> tokenLifetime = new AtomicReference<>(Duration.ofMinutes(1));

        ScopePeer() throws Exception {
            var local = Files.createTempFile("pipeline-scope-http", ".p12");
            Files.delete(local);
            var password = "synthetic-password";
            try {
                var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
                var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "local", "-keyalg", "RSA",
                        "-dname", "CN=local-test", "-ext", "SAN=ip:127.0.0.1", "-validity", "1", "-storetype", "PKCS12",
                        "-keystore", local.toString(), "-storepass", password, "-noprompt").redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                if (process.waitFor() != 0) throw new IllegalStateException("Test TLS certificate generation failed");
                var store = KeyStore.getInstance("PKCS12");
                try (var input = Files.newInputStream(local)) { store.load(input, password.toCharArray()); }
                var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(store, password.toCharArray());
                var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(store);
                var tls = SSLContext.getInstance("TLS");
                tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
                this.client = HttpClient.newBuilder().sslContext(tls).build();
                this.server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setHttpsConfigurator(new HttpsConfigurator(tls));
            } finally {
                Files.deleteIfExists(local);
            }
            var path = "/internal/v1/capture/machine-scope-checks";
            this.uri = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + path);
            server.createContext(path, exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-recipient-token");
                    assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION);
                    assertThat(body.get("tenantId").asString()).isEqualTo(CAPTURE);
                    assertThat(body.size()).isEqualTo(9);
                    lastRequest.set(body.get("requestId").asString());
                    var revision = body.get("scopeRevision").asString();
                    if ("expired-response".equals(revision)) {
                        try { Thread.sleep(1400); }
                        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                    var reply = JSON.createObjectNode()
                            .put("requestId", "mismatched-reply".equals(revision) ? UUID.randomUUID().toString() : lastRequest.get())
                            .put("correlationId", CORRELATION)
                            .put("decision", "revoked".equals(revision) ? "DENIED" : "AUTHORIZED");
                    byte[] bytes = JSON.writeValueAsBytes(reply);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        @Override public void close() { server.stop(0); client.close(); }
    }
}
