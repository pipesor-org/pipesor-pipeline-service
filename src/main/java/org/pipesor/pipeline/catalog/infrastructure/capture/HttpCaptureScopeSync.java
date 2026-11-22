package org.pipesor.pipeline.catalog.infrastructure.capture;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.pipesor.pipeline.catalog.application.ports.CaptureScopeSync;
import org.pipesor.pipeline.shared.infrastructure.http.BoundedBody;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.pipesor.pipeline.shared.security.WorkloadTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ingestion capture workflow scopes ({@code PUT /internal/v1/capture/workflow-scopes/{repositoryId}/{workflowId}},
 * role {@code Pipeline.CaptureScope.Write}, ADR-023). The call carries only a workload token; there is
 * no human session. Failures are logged with a fixed reason and the correlation id, never with payloads,
 * and are returned as outcomes so callers can leave repair to reconciliation.
 */
public final class HttpCaptureScopeSync implements CaptureScopeSync {
    public static final String ROLE = "Pipeline.CaptureScope.Write";
    public static final String PATH = "/internal/v1/capture/workflow-scopes";
    private static final Logger LOG = LoggerFactory.getLogger(HttpCaptureScopeSync.class);
    private static final Pattern GITHUB_ID = Pattern.compile("[1-9][0-9]{0,19}");
    private static final Pattern JSON_TYPE = Pattern.compile("(?i)application/(?:problem\\+)?json(?:\\s*;.*)?");
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final HttpClient http;
    private final TransportPolicy transport;
    private final URI ingestion;
    private final Supplier<WorkloadTokens.Token> tokens;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final Clock clock;

    public HttpCaptureScopeSync(HttpClient http, TransportPolicy transport, URI ingestion,
            Supplier<WorkloadTokens.Token> tokens, Duration timeout, int maxResponseBytes, Clock clock) {
        this.http = Objects.requireNonNull(http);
        this.transport = Objects.requireNonNull(transport);
        this.ingestion = transport.resolve(ingestion, PATH);
        this.tokens = Objects.requireNonNull(tokens);
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
        if (http.followRedirects() != HttpClient.Redirect.NEVER || timeout.isNegative() || timeout.isZero()
                || maxResponseBytes < 1) {
            throw new IllegalArgumentException("A non-redirecting client, a timeout and a response limit are required");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    record ScopeResponse(String repositoryId, String workflowId, String pipelineId, String status,
                         String scopeRevision, Long revision) {}
    record Problem(String code) {}

    @Override
    public Result sync(Scope scope, String correlationId) {
        String correlation = correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
        if (!GITHUB_ID.matcher(scope.githubRepositoryId()).matches() || !GITHUB_ID.matcher(scope.githubWorkflowId()).matches()) {
            return failed(Outcome.INVALID_REQUEST, "invalid github identifiers", correlation, scope);
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var token = tokens.get();
            Instant now = clock.instant();
            if (token == null || token.value() == null || token.value().isBlank()
                    || token.expiresAt() == null || !token.expiresAt().isAfter(now)) {
                return failed(Outcome.UNAVAILABLE, "workload token unavailable", correlation, scope);
            }
            var uri = transport.resolve(ingestion, "/" + scope.githubRepositoryId() + "/" + scope.githubWorkflowId());
            var body = new LinkedHashMap<String, Object>();
            body.put("requestId", UUID.randomUUID().toString());
            body.put("correlationId", correlation);
            body.put("pipelineId", scope.pipelineId().toString());
            body.put("monitoringStatus", scope.status().name());
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("X-Correlation-ID", correlation)
                    .header("Authorization", "Bearer " + token.value())
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
            pending = http.sendAsync(request, BoundedBody.handler(maxResponseBytes));
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return interpret(scope, correlation, response);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(Outcome.UNAVAILABLE, "interrupted", correlation, scope);
        } catch (Exception failure) {
            LOG.warn("Capture scope sync unavailable: correlation={}, pipeline={}, reason=unreachable, type={}",
                    correlation, scope.pipelineId(), rootType(failure));
            return Result.failed(Outcome.UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private Result interpret(Scope scope, String correlation, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        boolean json = JSON_TYPE.matcher(response.headers().firstValue("Content-Type").orElse("")).matches();
        if (status == 200) {
            ScopeResponse stored = json ? parse(response.body(), ScopeResponse.class) : null;
            if (stored == null || !scope.githubRepositoryId().equals(stored.repositoryId())
                    || !scope.githubWorkflowId().equals(stored.workflowId())
                    || !scope.pipelineId().toString().equalsIgnoreCase(String.valueOf(stored.pipelineId()))
                    || !scope.status().name().equals(stored.status())
                    || stored.scopeRevision() == null || stored.scopeRevision().isBlank()
                    || stored.revision() == null || stored.revision() < 0) {
                return failed(Outcome.RESPONSE_INVALID, "response does not echo the request", correlation, scope, status);
            }
            return new Result(Outcome.SYNCED, stored.scopeRevision(), stored.revision());
        }
        String code = json ? codeOf(response.body()) : null;
        if (status == 404 && "REPOSITORY_NOT_ADMITTED".equals(code)) {
            return failed(Outcome.REPOSITORY_NOT_ADMITTED, "repository not admitted", correlation, scope, status);
        }
        if (status == 409 && "PIPELINE_MISMATCH".equals(code)) {
            return failed(Outcome.PIPELINE_MISMATCH, "scope bound to another pipeline", correlation, scope, status);
        }
        if (status == 400) return failed(Outcome.INVALID_REQUEST, "request rejected as invalid", correlation, scope, status);
        if (status == 401 || status == 403) return failed(Outcome.DENIED, "workload token refused", correlation, scope, status);
        if (status >= 500) return failed(Outcome.UNAVAILABLE, "ingestion unavailable", correlation, scope, status);
        return failed(Outcome.RESPONSE_INVALID, "unexpected status", correlation, scope, status);
    }

    private static String codeOf(byte[] body) {
        var problem = parse(body, Problem.class);
        return problem == null ? null : problem.code();
    }

    private static <T> T parse(byte[] body, Class<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Result failed(Outcome outcome, String reason, String correlation, Scope scope, int status) {
        LOG.warn("Capture scope sync failed: correlation={}, pipeline={}, outcome={}, reason={}, status={}",
                correlation, scope.pipelineId(), outcome, reason, status);
        return Result.failed(outcome);
    }

    private static Result failed(Outcome outcome, String reason, String correlation, Scope scope) {
        LOG.warn("Capture scope sync failed: correlation={}, pipeline={}, outcome={}, reason={}",
                correlation, scope.pipelineId(), outcome, reason);
        return Result.failed(outcome);
    }

    private static String rootType(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root == failure ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + "/" + root.getClass().getSimpleName();
    }
}
