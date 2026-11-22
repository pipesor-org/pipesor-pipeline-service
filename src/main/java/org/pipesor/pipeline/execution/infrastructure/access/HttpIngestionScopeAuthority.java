package org.pipesor.pipeline.execution.infrastructure.access;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceAuthority.Permission;
import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;
import org.pipesor.pipeline.execution.interfaces.rest.validation.OwnerValidationJson;
import org.pipesor.pipeline.shared.infrastructure.http.BoundedBody;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.pipesor.pipeline.shared.security.WorkloadTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks Ingestion whether the exact-revision machine scope is still current
 * ({@code POST /internal/v1/capture/machine-scope-checks}, role {@code Pipeline.MachineScope.Check}).
 * Every failure is {@link Permission#UNAVAILABLE}.
 */
public final class HttpIngestionScopeAuthority {
    private static final Logger LOG = LoggerFactory.getLogger(HttpIngestionScopeAuthority.class);
    public static final String PATH = "/internal/v1/capture/machine-scope-checks";
    public static final String ROLE = "Pipeline.MachineScope.Check";
    private final HttpClient http;
    private final URI uri;
    private final Supplier<WorkloadTokens.Token> tokens;
    private final int maxResponseBytes;
    private final Clock clock;

    public HttpIngestionScopeAuthority(HttpClient http, TransportPolicy transport, URI ingestion,
            Supplier<WorkloadTokens.Token> tokens, int maxResponseBytes, Clock clock) {
        this.http = Objects.requireNonNull(http);
        this.uri = transport.resolve(ingestion, PATH);
        this.tokens = Objects.requireNonNull(tokens);
        this.clock = Objects.requireNonNull(clock);
        if (http.followRedirects() != HttpClient.Redirect.NEVER || maxResponseBytes < 1) {
            throw new IllegalArgumentException("A non-redirecting client and a response limit are required");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    public Permission verify(OperationalReferenceQuery query, Instant deadline, long endNanos) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            remaining(deadline, endNanos);
            var token = tokens.get();
            if (token == null || token.value() == null || token.value().isBlank()
                    || token.expiresAt() == null || !token.expiresAt().isAfter(clock.instant())) {
                return Permission.UNAVAILABLE;
            }
            Instant outgoingDeadline = deadline.isBefore(token.expiresAt()) ? deadline : token.expiresAt();
            var id = UUID.randomUUID();
            byte[] body = OwnerValidationJson.JSON.writeValueAsBytes(Map.ofEntries(
                    Map.entry("requestId",id.toString()),Map.entry("correlationId",query.correlationId()),
                    Map.entry("deadlineAt",outgoingDeadline.toString()),Map.entry("tenantId",query.tenantId().toString()),
                    Map.entry("githubAccountId",query.githubAccountId()),Map.entry("githubInstallationId",query.githubInstallationId()),
                    Map.entry("githubRepositoryId",query.githubRepositoryId()),Map.entry("githubWorkflowId",query.githubWorkflowId()),
                    Map.entry("scopeRevision",query.scopeRevision())));
            var request = HttpRequest.newBuilder(uri).timeout(remaining(outgoingDeadline,endNanos))
                    .header("Content-Type","application/json").header("Accept","application/json")
                    .header("X-Correlation-Id",query.correlationId()).header("Authorization","Bearer " + token.value())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = http.sendAsync(request, BoundedBody.handler(maxResponseBytes));
            var response = pending.get(remaining(outgoingDeadline,endNanos).toNanos(),TimeUnit.NANOSECONDS);
            remaining(outgoingDeadline,endNanos);
            if (response.statusCode() != 200 || !response.headers().allValues("Cache-Control").equals(List.of("no-store"))
                    || !response.headers().firstValue("Content-Type").orElse("")
                    .matches("(?i)application/json(?:\\s*;.*)?")) {
                LOG.warn("Machine scope check rejected: correlation={}, status={}", query.correlationId(), response.statusCode());
                return Permission.UNAVAILABLE;
            }
            var decision = Permission.valueOf(OwnerValidationJson.decision(response.body(),id,query.correlationId()));
            remaining(outgoingDeadline,endNanos);
            return decision;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Permission.UNAVAILABLE;
        } catch (Exception unavailable) {
            LOG.warn("Machine scope check unavailable: correlation={}, type={}", query.correlationId(),
                    rootType(unavailable));
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

    private static String rootType(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root == failure ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + "/" + root.getClass().getSimpleName();
    }
}
