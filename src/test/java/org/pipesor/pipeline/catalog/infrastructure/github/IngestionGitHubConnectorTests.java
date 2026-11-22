package org.pipesor.pipeline.catalog.infrastructure.github;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.services.GitHubConnectionRequired;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.pipesor.pipeline.shared.security.WorkloadTokens;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire mapping of the Ingestion GitHub facts routes against a loopback stand-in. */
class IngestionGitHubConnectorTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT = "24c96be0-50f3-4064-b5e4-643088141ae8";
    private static final String OID = "c6419aef-19d8-5131-bc13-8e9e6e43b727";
    private static final String ACTOR = TENANT + ":" + OID;
    private static final String RECIPIENT = "3f0a4b55-6a35-4a1a-8a43-7e9d4c1cb0a2";
    private HttpServer server;
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> lastHeaders = new AtomicReference<>();
    private volatile int status = 200;
    private volatile Function<JsonNode, ObjectNode> reply = IngestionGitHubConnectorTests::access;
    private volatile Optional<Jwt> session = Optional.of(session());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of(IngestionGitHubConnector.ACCESS_PATH, IngestionGitHubConnector.WORKFLOW_PATH)) {
            server.createContext(path, exchange -> {
                try (exchange) {
                    var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    lastBody.set(body);
                    lastHeaders.set(exchange.getRequestHeaders());
                    byte[] bytes = status == 200 ? JSON.writeValueAsBytes(reply.apply(body))
                            : ("{\"status\":" + status + "}").getBytes();
                    exchange.getResponseHeaders().set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
        }
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private IngestionGitHubConnector connector() {
        return new IngestionGitHubConnector(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new TransportPolicy(true), URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                () -> new WorkloadTokens.Token("workload-token", Instant.now().plusSeconds(300)), () -> session,
                () -> "corr-github", Duration.ofSeconds(3), 65536, 2, Clock.systemUTC());
    }

    @Test
    void sendsWorkloadAndSubjectTokensAndMapsFacts() {
        var access = connector().verifyAccess(new GitHubConnector.AccessRequest(ACTOR, ACTOR, Set.of("823001"),
                GitHubConnector.Operation.VIEW));
        assertThat(lastHeaders.get().getFirst("Authorization")).isEqualTo("Bearer workload-token");
        assertThat(lastHeaders.get().getFirst("X-Pipesor-Subject-Token")).isEqualTo("session-token");
        assertThat(lastHeaders.get().getFirst("X-Correlation-Id")).isEqualTo("corr-github");
        assertThat(lastBody.get().get("operation").asString()).isEqualTo("VIEW");
        assertThat(lastBody.get().has("recipientObjectId")).isFalse();
        assertThat(access.actorRef()).isEqualTo(ACTOR);
        assertThat(access.subjectRef()).isEqualTo(ACTOR);
        var repository = access.repositories().get("823001");
        assertThat(repository.installationId()).isEqualTo("5001");
        assertThat(repository.accountId()).isEqualTo("7001");
        assertThat(repository.readable()).isTrue();
    }

    @Test
    void batchesLargeSelectionsAndSendsGrantRecipients() {
        var access = connector().verifyAccess(new GitHubConnector.AccessRequest(ACTOR, TENANT + ":" + RECIPIENT,
                Set.of("1", "2", "3"), GitHubConnector.Operation.GRANT_VIEW));
        assertThat(access.repositories()).containsOnlyKeys("1", "2", "3");
        assertThat(lastBody.get().get("recipientObjectId").asString()).isEqualTo(RECIPIENT);
        assertThatThrownBy(() -> connector().verifyAccess(new GitHubConnector.AccessRequest(ACTOR,
                UUID.randomUUID() + ":" + RECIPIENT, Set.of("1"), GitHubConnector.Operation.GRANT_VIEW)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void verifiesWorkflowsForRegistration() {
        reply = body -> {
            var result = JSON.createObjectNode();
            result.set("access", access(((ObjectNode) body.deepCopy())
                    .put("operation", "REGISTER").set("repositoryIds", JSON.createArrayNode().add("823001"))));
            return result.put("repositoryId", "823001").put("workflowId", "918001").put("fullName", "owner/service")
                    .put("defaultBranch", "main").put("workflowName", "Build").put("workflowFile", ".github/workflows/build.yml");
        };
        var workflow = connector().verifyWorkflow(ACTOR, "823001", "918001");
        assertThat(workflow.fullName()).isEqualTo("owner/service");
        assertThat(workflow.access().operation()).isEqualTo(GitHubConnector.Operation.REGISTER);
        assertThat(lastBody.get().get("workflowId").asString()).isEqualTo("918001");
    }

    @Test
    void mapsIngestionOutcomesAndFailsClosed() {
        var request = new GitHubConnector.AccessRequest(ACTOR, ACTOR, Set.of("823001"), GitHubConnector.Operation.VIEW);
        status = 409;
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubConnectionRequired.class);
        status = 403;
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(SecurityException.class);
        status = 503;
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
        status = 200;
        reply = body -> access(body).put("requestId", "other");
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
        reply = body -> access(body).put("operation", "CONFIGURE");
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
        reply = body -> access(((ObjectNode) body.deepCopy()).set("repositoryIds", JSON.createArrayNode().add("999")));
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
        reply = body -> JSON.createObjectNode().put("unexpected", true);
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
        session = Optional.empty();
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
        session = Optional.of(session());
        var otherActor = new GitHubConnector.AccessRequest(TENANT + ":" + RECIPIENT, TENANT + ":" + RECIPIENT, Set.of("1"),
                GitHubConnector.Operation.VIEW);
        assertThatThrownBy(() -> connector().verifyAccess(otherActor)).isInstanceOf(GitHubVerificationUnavailable.class);
        server.stop(0);
        assertThatThrownBy(() -> connector().verifyAccess(request)).isInstanceOf(GitHubVerificationUnavailable.class);
    }

    @Test
    void emptySelectionsDiscloseNothingWithoutCallingIngestion() {
        server.stop(0);
        var access = connector().verifyAccess(new GitHubConnector.AccessRequest(ACTOR, ACTOR, Set.of(),
                GitHubConnector.Operation.VIEW));
        assertThat(access.repositories()).isEmpty();
        assertThat(access.githubUserId()).isEqualTo("87343021");
    }

    private static ObjectNode access(JsonNode request) {
        var repositories = JSON.createArrayNode();
        request.get("repositoryIds").forEach(id -> repositories.add(JSON.createObjectNode().put("repositoryId", id.asString())
                .put("installationId", "5001").put("accountId", "7001").put("admittedForTenant", true)
                .put("readable", true).put("administrator", false)));
        String subject = request.has("recipientObjectId") ? request.get("recipientObjectId").asString() : OID;
        var now = Instant.now();
        var result = JSON.createObjectNode().put("requestId", request.get("requestId").asString())
                .put("correlationId", request.get("correlationId").asString()).put("tenantId", TENANT)
                .put("actorObjectId", OID).put("subjectObjectId", subject)
                .put("operation", request.get("operation").asString()).put("githubUserId", "87343021")
                .put("generationId", UUID.randomUUID().toString()).put("revision", "1")
                .put("observedAt", now.minusMillis(5).toString()).put("validUntil", now.plusSeconds(30).toString());
        result.set("repositories", repositories);
        return result;
    }

    private static Jwt session() {
        return Jwt.withTokenValue("session-token").header("alg", "RS256").claim("tid", TENANT).claim("oid", OID)
                .claim("github_user_id", "87343021").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
    }
}
