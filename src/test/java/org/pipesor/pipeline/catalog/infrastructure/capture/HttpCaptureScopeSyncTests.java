package org.pipesor.pipeline.catalog.infrastructure.capture;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync.Outcome;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync.Scope;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.pipesor.pipeline.shared.security.WorkloadTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wire mapping of the Ingestion capture workflow scope route against a loopback stand-in. */
class HttpCaptureScopeSyncTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PIPELINE = UUID.fromString("0b6d0e4e-7f0c-4f0e-9a51-6c1e3b0f8a11");
    private static final Scope SCOPE = new Scope("824001", "919001", PIPELINE, MonitoringStatus.PAUSED);
    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile Function<JsonNode, byte[]> reply = HttpCaptureScopeSyncTests::echo;
    private volatile Headers extraHeaders = new Headers();
    private volatile long delayMillis;
    private volatile WorkloadTokens.Token token = new WorkloadTokens.Token("workload-token", Instant.now().plusSeconds(300));

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                method.set(exchange.getRequestMethod());
                path.set(exchange.getRequestURI().getRawPath());
                lastHeaders.set(exchange.getRequestHeaders());
                var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                lastBody.set(body);
                if (delayMillis > 0) Thread.sleep(delayMillis);
                byte[] bytes = reply.apply(body);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().putAll(extraHeaders);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private HttpCaptureScopeSync adapter() { return adapter(Duration.ofSeconds(2), 4096); }

    private HttpCaptureScopeSync adapter(Duration timeout, int maxBytes) {
        return new HttpCaptureScopeSync(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new TransportPolicy(true), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/base/"),
                () -> token, timeout, maxBytes, Clock.systemUTC());
    }

    @Test
    void putsTheDesiredScopeWithWorkloadTokenAndCorrelation() {
        var result = adapter().sync(SCOPE, "corr-123");
        assertThat(result.outcome()).isEqualTo(Outcome.SYNCED);
        assertThat(result.scopeRevision()).isEqualTo("scope-7");
        assertThat(result.revision()).isEqualTo(7L);
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/base/internal/v1/capture/workflow-scopes/824001/919001");
        var headers = lastHeaders.get();
        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer workload-token");
        assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(headers.getFirst("X-Correlation-ID")).isEqualTo("corr-123");
        assertThat(headers.containsKey("X-Pipesor-Subject-Token")).isFalse();
        var body = lastBody.get();
        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("requestId", "correlationId", "pipelineId", "monitoringStatus");
        assertThat(UUID.fromString(body.get("requestId").asString())).isNotNull();
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-123");
        assertThat(body.get("pipelineId").asString()).isEqualTo(PIPELINE.toString());
        assertThat(body.get("monitoringStatus").asString()).isEqualTo("PAUSED");
    }

    @Test
    void eachCallUsesAFreshRequestIdAndActiveIsSentAsActive() {
        var sync = adapter();
        sync.sync(SCOPE, "c");
        String first = lastBody.get().get("requestId").asString();
        var active = new Scope("824001", "919001", PIPELINE, MonitoringStatus.ACTIVE);
        assertThat(sync.sync(active, "c").outcome()).isEqualTo(Outcome.SYNCED);
        assertThat(lastBody.get().get("requestId").asString()).isNotEqualTo(first);
        assertThat(lastBody.get().get("monitoringStatus").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void responsesThatDoNotEchoTheRequestAreInvalid() {
        for (String field : new String[] {"repositoryId", "workflowId", "pipelineId", "status"}) {
            reply = request -> {
                var node = echoNode(request);
                node.put(field, "pipelineId".equals(field) ? UUID.randomUUID().toString()
                        : "status".equals(field) ? "ACTIVE" : "1");
                return JSON.writeValueAsBytes(node);
            };
            assertThat(adapter().sync(SCOPE, "c").outcome()).as(field).isEqualTo(Outcome.RESPONSE_INVALID);
        }
        reply = request -> { var node = echoNode(request); node.remove("scopeRevision"); return JSON.writeValueAsBytes(node); };
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.RESPONSE_INVALID);
        reply = request -> { var node = echoNode(request); node.remove("revision"); return JSON.writeValueAsBytes(node); };
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.RESPONSE_INVALID);
        reply = request -> "not json".getBytes();
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.RESPONSE_INVALID);
        reply = HttpCaptureScopeSyncTests::echo;
        contentType = "text/plain";
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.RESPONSE_INVALID);
    }

    @Test
    void mapsEachDocumentedError() {
        assertThat(problem(404, "REPOSITORY_NOT_ADMITTED")).isEqualTo(Outcome.REPOSITORY_NOT_ADMITTED);
        assertThat(problem(409, "PIPELINE_MISMATCH")).isEqualTo(Outcome.PIPELINE_MISMATCH);
        assertThat(problem(400, "INVALID_REQUEST")).isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(problem(503, "UNAVAILABLE")).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(problem(500, "INTERNAL_ERROR")).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(problem(401, "UNAUTHENTICATED")).isEqualTo(Outcome.DENIED);
        assertThat(problem(403, "ACCESS_DENIED")).isEqualTo(Outcome.DENIED);
        // A 404/409 without the documented code (e.g. route missing) is not mistaken for a domain answer.
        assertThat(problem(404, "NOT_FOUND")).isEqualTo(Outcome.RESPONSE_INVALID);
        assertThat(problem(409, "CONFLICT")).isEqualTo(Outcome.RESPONSE_INVALID);
        assertThat(problem(422, "OTHER")).isEqualTo(Outcome.RESPONSE_INVALID);
    }

    @Test
    void redirectsAreNotFollowed() {
        status = 307;
        reply = request -> new byte[0];
        var location = new Headers();
        location.set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere");
        extraHeaders = location;
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.RESPONSE_INVALID);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void oversizedResponsesAndTimeoutsAreUnavailable() {
        reply = request -> new byte[8192];
        assertThat(adapter(Duration.ofSeconds(2), 1024).sync(SCOPE, "c").outcome()).isEqualTo(Outcome.UNAVAILABLE);
        reply = HttpCaptureScopeSyncTests::echo;
        delayMillis = 1500;
        assertThat(adapter(Duration.ofMillis(200), 4096).sync(SCOPE, "c").outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void unreachableIngestionIsUnavailable() {
        server.stop(0);
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    void invalidIdentifiersAndExpiredTokensAreNotSent() {
        assertThat(adapter().sync(new Scope("824/../1", "919001", PIPELINE, MonitoringStatus.ACTIVE), "c").outcome())
                .isEqualTo(Outcome.INVALID_REQUEST);
        assertThat(adapter().sync(new Scope("824001", "abc", PIPELINE, MonitoringStatus.ACTIVE), "c").outcome())
                .isEqualTo(Outcome.INVALID_REQUEST);
        token = new WorkloadTokens.Token("expired", Instant.now().minusSeconds(1));
        assertThat(adapter().sync(SCOPE, "c").outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(requests.get()).isZero();
    }

    @Test
    void requiresANonRedirectingClientAndAllowedTransport() {
        var redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        assertThatThrownBy(() -> new HttpCaptureScopeSync(redirecting, new TransportPolicy(true),
                URI.create("http://127.0.0.1:1"), () -> token, Duration.ofSeconds(1), 1024, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        var plain = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        assertThatThrownBy(() -> new HttpCaptureScopeSync(plain, new TransportPolicy(false),
                URI.create("http://127.0.0.1:1"), () -> token, Duration.ofSeconds(1), 1024, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Outcome problem(int code, String problemCode) {
        status = code;
        contentType = "application/problem+json";
        reply = request -> ("{\"status\":" + code + ",\"code\":\"" + problemCode + "\",\"correlationId\":\"c\"}").getBytes();
        return adapter().sync(SCOPE, "c").outcome();
    }

    private static ObjectNode echoNode(JsonNode request) {
        var node = JSON.createObjectNode();
        node.put("repositoryId", "824001");
        node.put("workflowId", "919001");
        node.put("pipelineId", request.get("pipelineId").asString());
        node.put("status", request.get("monitoringStatus").asString());
        node.put("scopeRevision", "scope-7");
        node.put("revision", 7);
        return node;
    }

    private static byte[] echo(JsonNode request) { return JSON.writeValueAsBytes(echoNode(request)); }
}
