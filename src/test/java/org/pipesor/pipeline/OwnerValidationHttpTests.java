package org.pipesor.pipeline;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.execution.application.commands.ExecutionSnapshot;
import org.pipesor.pipeline.execution.application.services.ExecutionSnapshotService;
import org.pipesor.pipeline.execution.interfaces.messaging.transform.ExecutionSnapshotTransformer;
import org.pipesor.pipeline.shared.domain.model.DeterministicIds;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.security.TestIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Internal check over real HTTP: platform caller rules, wire limits and the Ingestion machine-scope peer. */
class OwnerValidationHttpTests extends PostgresIntegrationTest {
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
    private static final ScopePeer SCOPE = new ScopePeer();

    @Autowired RestTestClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ExecutionSnapshotService snapshots;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void ingestion(DynamicPropertyRegistry registry) {
        registry.add("pipeline.ingestion.base-uri", () -> "http://127.0.0.1:" + SCOPE.server.getAddress().getPort());
        registry.add("pipeline.owner-validation.max-in-flight", () -> "1");
        registry.add("pipeline.owner-validation.max-request-bytes", () -> "2048");
    }

    @BeforeEach
    void resetPeer() { SCOPE.calls.set(0); }

    private static String logEvent() { return TestIdentity.workload("log-event", "Evidence.Check"); }
    private static String correlation() { return TestIdentity.workload("correlation", "Correlation.OperationalReferences.Check"); }

    @Test
    void acceptsOnlyTheCatalogCallersEachWithItsOwnRole() {
        post(body(), null).expectStatus().isUnauthorized().expectHeader().valueEquals("Cache-Control", "no-store");
        post(body(), "not-a-jwt").expectStatus().isUnauthorized();
        post(body(), TestIdentity.session(TestIdentity.GITHUB_USER)).expectStatus().isForbidden();
        post(body(), TestIdentity.workload("recommendation", "Recommendation.DiagnosisEntitlements.Resolve")).expectStatus().isForbidden();
        // A trusted caller presenting the other caller's role is rejected by the verifier.
        post(body(), TestIdentity.workload("correlation", "Evidence.Check")).expectStatus().isUnauthorized();
        post(body(), TestIdentity.workload("log-event", "Correlation.OperationalReferences.Check")).expectStatus().isUnauthorized();
        assertThat(SCOPE.calls).hasValue(0);
        post(body(), correlation()).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.decision").isEqualTo("NOT_READY");
        post(body(), logEvent()).expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("NOT_READY");
        assertThat(SCOPE.calls).hasValue(2);
        client.get().uri(PATH).header("Authorization", "Bearer " + logEvent()).exchange().expectStatus().isForbidden();
    }

    @Test
    void enforcesTheWireContractBeforeAnyLookup() {
        var token = logEvent();
        client.post().uri(PATH).header("Authorization", "Bearer " + token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(body())).exchange().expectStatus().isBadRequest();
        client.post().uri(PATH).header("Authorization", "Bearer " + token).header("X-Correlation-Id", CORRELATION)
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN).body("{}").exchange().expectStatus().isEqualTo(415);
        post(body().put("padding", "x".repeat(3000)), token).expectStatus().isEqualTo(413);
        post(body().put("unexpected", "x"), token).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
        assertThat(SCOPE.calls).hasValue(0);
    }

    @Test
    void aStalledBodyDoesNotHoldTheOnlyProcessingSlot() throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            String request = "POST " + PATH + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\nAuthorization: Bearer " + logEvent() + "\r\n"
                    + "X-Correlation-Id: " + CORRELATION + "\r\nContent-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n1\r\n{\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(300);
            post(body(), logEvent()).expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("NOT_READY");
            socket.setSoTimeout(20000);
            var reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String status = reply.readLine();
            assertThat(status == null || !status.contains(" 200 ")).isTrue();
        }
    }

    @Test
    void checksCallerThenCurrentScopeThenCommittedOrigin() {
        var repository = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.repositories (id, github_repository_id, full_name, created_at) VALUES (?, ?, 'owner/http', now())",
                repository, REPOSITORY);
        jdbc.update("""
            INSERT INTO ops.pipelines (id, repository_id, github_workflow_id, workflow_name, workflow_file,
                criticality, monitoring_status, created_at) VALUES (?, ?, ?, 'Owner test', 'owner.yml', 'HIGH', 'ACTIVE', now())
            """, PIPELINE, repository, WORKFLOW);
        try {
            var authorized = logEvent();
            var request = body();
            post(request, authorized).expectStatus().isOk().expectBody()
                    .jsonPath("$.decision").isEqualTo("NOT_READY")
                    .jsonPath("$.requestId").isEqualTo(request.get("requestId").asString());
            assertThat(SCOPE.lastRequest.get()).isNotEqualTo(request.get("requestId").asString());
            var claims = TestIdentity.claims(SCOPE.lastAuthorization.get());
            assertThat(claims.get("iss")).isEqualTo("urn:pipesor:workload:pipeline");
            assertThat(claims.get("aud")).isEqualTo("urn:pipesor:api:ingestion");
            assertThat(claims.get("roles")).isEqualTo(List.of("Pipeline.MachineScope.Check"));

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
            post(body().put("deadlineAt", Instant.now().minusSeconds(1).toString()), authorized).expectStatus().isEqualTo(503);
            post(body().put("deadlineAt", "malformedZ"), authorized).expectStatus().isBadRequest();
            post(body().put("scopeRevision", "slow-response").put("deadlineAt", Instant.now().plusSeconds(1).toString()), authorized)
                    .expectStatus().isEqualTo(503);
            assertThat(SCOPE.calls).hasValue(6);
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

    /** Loopback Ingestion stand-in for {@code POST /internal/v1/capture/machine-scope-checks}. */
    private static final class ScopePeer {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastRequest = new AtomicReference<>();
        final AtomicReference<String> lastAuthorization = new AtomicReference<>();

        ScopePeer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
            server.createContext("/internal/v1/capture/machine-scope-checks", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()));
                    assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION);
                    assertThat(body.get("tenantId").asString()).isEqualTo(CAPTURE);
                    assertThat(body.size()).isEqualTo(9);
                    lastRequest.set(body.get("requestId").asString());
                    var revision = body.get("scopeRevision").asString();
                    if ("slow-response".equals(revision)) {
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
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }
    }
}
