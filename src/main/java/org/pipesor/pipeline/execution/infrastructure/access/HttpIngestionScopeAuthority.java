package org.pipesor.pipeline.execution.infrastructure.access;

import com.azure.core.credential.AccessToken;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceAuthority.Permission;
import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;
import org.pipesor.pipeline.execution.interfaces.rest.validation.OwnerValidationJson;

public final class HttpIngestionScopeAuthority {
    @FunctionalInterface
    public interface TokenSource { AccessToken acquire(Duration remaining); }
    private final HttpClient http;
    private final URI uri;
    private final TokenSource tokens;
    private final int maxResponseBytes;
    private final Clock clock;

    public HttpIngestionScopeAuthority(HttpClient http, URI uri, TokenSource tokens, int maxResponseBytes, Clock clock) {
        this.http = Objects.requireNonNull(http);
        this.uri = Objects.requireNonNull(uri);
        this.tokens = Objects.requireNonNull(tokens);
        this.clock = Objects.requireNonNull(clock);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !"/internal/v1/capture/machine-scope-checks".equals(uri.getPath())
                || http.followRedirects() != HttpClient.Redirect.NEVER || maxResponseBytes < 1) {
            throw new IllegalArgumentException("Explicit HTTPS scope endpoint and response limit required");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    public Permission verify(OperationalReferenceQuery query, Instant deadline, long endNanos) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var token = tokens.acquire(remaining(deadline,endNanos));
            if (token == null || token.getToken() == null || token.getToken().isBlank()
                    || token.getExpiresAt() == null || !token.getExpiresAt().toInstant().isAfter(clock.instant())) {
                return Permission.UNAVAILABLE;
            }
            Instant outgoingDeadline = deadline.isBefore(token.getExpiresAt().toInstant()) ? deadline : token.getExpiresAt().toInstant();
            var id = UUID.randomUUID();
            byte[] body = OwnerValidationJson.JSON.writeValueAsBytes(Map.ofEntries(
                    Map.entry("requestId",id.toString()),Map.entry("correlationId",query.correlationId()),
                    Map.entry("deadlineAt",outgoingDeadline.toString()),Map.entry("tenantId",query.tenantId().toString()),
                    Map.entry("githubAccountId",query.githubAccountId()),Map.entry("githubInstallationId",query.githubInstallationId()),
                    Map.entry("githubRepositoryId",query.githubRepositoryId()),Map.entry("githubWorkflowId",query.githubWorkflowId()),
                    Map.entry("scopeRevision",query.scopeRevision())));
            var request = HttpRequest.newBuilder(uri).timeout(remaining(outgoingDeadline,endNanos))
                    .header("Content-Type","application/json").header("Accept","application/json")
                    .header("X-Correlation-Id",query.correlationId()).header("Authorization","Bearer " + token.getToken())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = http.sendAsync(request, ignored -> new BoundedBody(maxResponseBytes));
            var response = pending.get(remaining(outgoingDeadline,endNanos).toNanos(),TimeUnit.NANOSECONDS);
            remaining(outgoingDeadline,endNanos);
            if (response.statusCode() != 200 || !response.headers().allValues("Cache-Control").equals(List.of("no-store"))
                    || !response.headers().firstValue("Content-Type").orElse("")
                    .matches("(?i)application/json(?:\\s*;.*)?")) return Permission.UNAVAILABLE;
            var decision = Permission.valueOf(OwnerValidationJson.decision(response.body(),id,query.correlationId()));
            remaining(outgoingDeadline,endNanos);
            return decision;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Permission.UNAVAILABLE;
        } catch (Exception unavailable) {
            return Permission.UNAVAILABLE;
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private Duration remaining(Instant deadline, long endNanos) {
        long monotonic = endNanos - System.nanoTime();
        var wall = Duration.between(clock.instant(),deadline);
        if (monotonic <= 0 || wall.isNegative() || wall.isZero()) throw new IllegalStateException("Owner validation deadline exhausted");
        return wall.compareTo(Duration.ofNanos(monotonic)) < 0 ? wall : Duration.ofNanos(monotonic);
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int maxBytes) { this.maxBytes = maxBytes; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            result.whenComplete((unused, failure) -> { if (result.isCancelled()) value.cancel(); });
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > maxBytes - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Owner validation response too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IllegalStateException("Owner validation unavailable")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
