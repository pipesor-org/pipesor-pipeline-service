package org.pipesor.pipeline.catalog.infrastructure.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.pipesor.pipeline.catalog.application.ports.GitHubConnector;
import org.pipesor.pipeline.catalog.application.services.GitHubConnectionRequired;
import org.pipesor.pipeline.catalog.application.services.GitHubVerificationUnavailable;
import org.pipesor.pipeline.shared.infrastructure.http.BoundedBody;
import org.pipesor.pipeline.shared.security.TransportPolicy;
import org.pipesor.pipeline.shared.security.WorkloadTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub facts from Ingestion ({@code /internal/v1/github/**}). Each call carries a workload token
 * (target {@code ingestion}, role {@code Pipeline.GitHubFacts.Read}) and the verified session token of
 * the current request as subject token. Any failure other than an explicit denial or a missing GitHub
 * connection is {@link GitHubVerificationUnavailable}; there is no fallback to local grants.
 */
public final class IngestionGitHubConnector implements GitHubConnector {
    public static final String ROLE = "Pipeline.GitHubFacts.Read";
    public static final String SUBJECT_HEADER = "X-Pipesor-Subject-Token";
    static final String ACCESS_PATH = "/internal/v1/github/access-verifications";
    static final String WORKFLOW_PATH = "/internal/v1/github/workflow-verifications";
    private static final Logger LOG = LoggerFactory.getLogger(IngestionGitHubConnector.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** The verified human session behind the current request (own session or subject token). */
    public interface Sessions { Optional<Jwt> current(); }

    private final HttpClient http;
    private final URI accessUri;
    private final URI workflowUri;
    private final Supplier<WorkloadTokens.Token> tokens;
    private final Sessions sessions;
    private final Supplier<String> correlation;
    private final Duration timeout;
    private final Duration decisionBudget;
    private final int maxResponseBytes;
    private final int maxBatchSize;
    private final Clock clock;

    public IngestionGitHubConnector(HttpClient http, TransportPolicy transport, URI ingestion,
            Supplier<WorkloadTokens.Token> tokens, Sessions sessions, Supplier<String> correlation,
            Duration timeout, Duration decisionBudget, int maxResponseBytes, int maxBatchSize, Clock clock) {
        this.http = Objects.requireNonNull(http);
        this.accessUri = transport.resolve(ingestion, ACCESS_PATH);
        this.workflowUri = transport.resolve(ingestion, WORKFLOW_PATH);
        this.tokens = Objects.requireNonNull(tokens);
        this.sessions = Objects.requireNonNull(sessions);
        this.correlation = Objects.requireNonNull(correlation);
        this.timeout = Objects.requireNonNull(timeout);
        this.decisionBudget = Objects.requireNonNull(decisionBudget);
        this.clock = Objects.requireNonNull(clock);
        if (http.followRedirects() != HttpClient.Redirect.NEVER || timeout.isNegative() || timeout.isZero()
                || decisionBudget.isNegative()
                || maxResponseBytes < 1 || maxBatchSize < 1) {
            throw new IllegalArgumentException("A non-redirecting client, a timeout and limits are required");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.maxBatchSize = maxBatchSize;
    }

    record RepositoryFact(String repositoryId, String installationId, String accountId, Boolean admittedForTenant,
                          Boolean readable, Boolean administrator) {}
    record AccessVerification(String requestId, String correlationId, UUID tenantId, UUID actorObjectId,
                              UUID subjectObjectId, String operation, String githubUserId, UUID generationId,
                              String revision, Instant observedAt, Instant validUntil, List<RepositoryFact> repositories) {}
    record WorkflowVerification(AccessVerification access, String repositoryId, String workflowId, String fullName,
                                String defaultBranch, String workflowName, String workflowFile) {}

    /** {@code deadline} bounds the HTTP exchange; {@code decisionDeadline} is sent to Ingestion and also covers the local decision. */
    private record Call(Jwt session, String actorRef, String correlationId, Instant deadline, Instant decisionDeadline) {}

    @Override
    public Access verifyAccess(AccessRequest request) {
        var call = call(request.actorRef());
        UUID recipient = recipient(call, request);
        var repositories = List.copyOf(request.repositoryIds());
        if (repositories.isEmpty()) {
            // Ingestion verifies non-empty selections only; an empty selection discloses nothing.
            var now = clock.instant();
            return new Access(request.actorRef(), request.subjectRef(), request.operation(),
                    call.session().getClaimAsString("github_user_id"), true, now, call.deadline(), Map.of());
        }
        Access merged = null;
        for (int from = 0; from < repositories.size(); from += maxBatchSize) {
            var batch = repositories.subList(from, Math.min(repositories.size(), from + maxBatchSize));
            var body = new LinkedHashMap<String, Object>();
            String requestId = UUID.randomUUID().toString();
            body.put("requestId", requestId);
            body.put("correlationId", call.correlationId());
            body.put("deadline", call.decisionDeadline().toString());
            body.put("operation", request.operation().name());
            if (recipient != null) body.put("recipientObjectId", recipient.toString());
            body.put("repositoryIds", batch);
            var verified = parse(send(accessUri, body, call), AccessVerification.class, call);
            var access = access(verified, requestId, call, request.operation(), Set.copyOf(batch));
            merged = merged == null ? access : merge(merged, access);
        }
        return merged;
    }

    @Override
    public Workflow verifyWorkflow(String actorRef, String repositoryId, String workflowId) {
        var call = call(actorRef);
        var body = new LinkedHashMap<String, Object>();
        String requestId = UUID.randomUUID().toString();
        body.put("requestId", requestId);
        body.put("correlationId", call.correlationId());
        body.put("deadline", call.decisionDeadline().toString());
        body.put("operation", Operation.REGISTER.name());
        body.put("repositoryId", repositoryId);
        body.put("workflowId", workflowId);
        var verified = parse(send(workflowUri, body, call), WorkflowVerification.class, call);
        if (verified == null || verified.access() == null) throw unavailable(call, "workflow shape");
        var access = access(verified.access(), requestId, call, Operation.REGISTER, Set.of(repositoryId));
        return new Workflow(access, verified.repositoryId(), verified.workflowId(), verified.fullName(),
                verified.defaultBranch(), verified.workflowName(), verified.workflowFile());
    }

    private Call call(String actorRef) {
        var session = sessions.current().orElseThrow(() -> {
            LOG.warn("GitHub verification without a verified session: correlation={}", correlation.get());
            return new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.NO_VERIFIED_SESSION);
        });
        String sessionActor = session.getClaimAsString("tid") + ":" + session.getClaimAsString("oid");
        if (!sessionActor.equals(actorRef)) {
            LOG.warn("GitHub verification actor differs from the session subject: correlation={}", correlation.get());
            throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.SESSION_ACTOR_MISMATCH);
        }
        Instant now = clock.instant();
        Instant deadline = now.plus(timeout);
        // Facts stay valid for the local decision that follows the exchange, not only for the exchange itself.
        Instant decision = deadline.plus(decisionBudget);
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(decision)) decision = session.getExpiresAt();
        if (decision.isBefore(deadline)) deadline = decision;
        if (!deadline.isAfter(now)) throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.DEADLINE_EXCEEDED);
        return new Call(session, actorRef, correlation.get(), deadline, decision);
    }

    private static UUID recipient(Call call, AccessRequest request) {
        boolean grant = request.operation().name().startsWith("GRANT_");
        if (!grant) {
            if (!request.subjectRef().equals(request.actorRef())) {
                throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.FACTS_BINDING_MISMATCH);
            }
            return null;
        }
        var parts = request.subjectRef().split(":", -1);
        String tenant = call.session().getClaimAsString("tid");
        if (parts.length != 2 || !parts[0].equals(tenant)) throw new SecurityException("Recipient outside the tenant");
        return UUID.fromString(parts[1]);
    }

    private byte[] send(URI uri, Map<String, Object> body, Call call) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var token = tokens.get();
            var remaining = Duration.between(clock.instant(), call.deadline());
            if (remaining.isNegative() || remaining.isZero()) throw unavailable(call, "deadline");
            var request = HttpRequest.newBuilder(uri).timeout(remaining)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("X-Correlation-Id", call.correlationId())
                    .header("Authorization", "Bearer " + token.value())
                    .header(SUBJECT_HEADER, call.session().getTokenValue())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
            pending = http.sendAsync(request, BoundedBody.handler(maxResponseBytes));
            var response = pending.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status == 200 && response.headers().firstValue("Content-Type").orElse("")
                    .matches("(?i)application/json(?:\\s*;.*)?")) {
                return response.body();
            }
            if (status == 409) throw new GitHubConnectionRequired();
            if (status == 403) throw new SecurityException("GitHub access denied by Ingestion");
            LOG.warn("GitHub verification rejected: correlation={}, status={}", call.correlationId(), status);
            throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.CONNECTOR_REJECTED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.CONNECTOR_UNREACHABLE);
        } catch (SecurityException | GitHubVerificationUnavailable expected) {
            throw expected;
        } catch (Exception failure) {
            LOG.warn("GitHub verification unavailable: correlation={}, type={}", call.correlationId(),
                    rootType(failure));
            throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.CONNECTOR_UNREACHABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private Access access(AccessVerification verified, String requestId, Call call, Operation operation, Set<String> requested) {
        if (verified == null || !requestId.equals(verified.requestId()) || !call.correlationId().equals(verified.correlationId())
                || verified.tenantId() == null || verified.actorObjectId() == null || verified.subjectObjectId() == null
                || !operation.name().equals(verified.operation()) || verified.repositories() == null
                || verified.observedAt() == null || verified.validUntil() == null) {
            throw unavailable(call, "access binding");
        }
        String tenant = verified.tenantId().toString();
        var facts = new HashMap<String, RepositoryAccess>();
        for (var fact : verified.repositories()) {
            if (fact == null || fact.repositoryId() == null || fact.admittedForTenant() == null || fact.readable() == null
                    || fact.administrator() == null || facts.containsKey(fact.repositoryId())) {
                throw unavailable(call, "repository fact");
            }
            facts.put(fact.repositoryId(), new RepositoryAccess(fact.repositoryId(), fact.installationId(), fact.accountId(),
                    fact.admittedForTenant(), fact.readable(), fact.administrator()));
        }
        if (!facts.keySet().equals(requested)) throw unavailable(call, "repository selection");
        return new Access(tenant + ":" + verified.actorObjectId(), tenant + ":" + verified.subjectObjectId(), operation,
                verified.githubUserId(), true, verified.observedAt(), verified.validUntil(), facts);
    }

    private Access merge(Access first, Access next) {
        if (!first.actorRef().equals(next.actorRef()) || !first.subjectRef().equals(next.subjectRef())
                || !Objects.equals(first.githubUserId(), next.githubUserId())) {
            throw new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.FACTS_BINDING_MISMATCH);
        }
        var repositories = new HashMap<>(first.repositories());
        repositories.putAll(next.repositories());
        Instant verifiedAt = first.verifiedAt().isBefore(next.verifiedAt()) ? next.verifiedAt() : first.verifiedAt();
        Instant validUntil = first.validUntil().isBefore(next.validUntil()) ? first.validUntil() : next.validUntil();
        return new Access(first.actorRef(), first.subjectRef(), first.operation(), first.githubUserId(), true,
                verifiedAt, validUntil, repositories);
    }

    private <T> T parse(byte[] body, Class<T> type, Call call) {
        try {
            return JSON.readValue(body, type);
        } catch (RuntimeException malformed) {
            throw unavailable(call, "malformed " + type.getSimpleName());
        }
    }

    private GitHubVerificationUnavailable unavailable(Call call, String reason) {
        LOG.warn("GitHub verification response invalid: correlation={}, reason={}", call.correlationId(), reason);
        return new GitHubVerificationUnavailable(GitHubVerificationUnavailable.Reason.RESPONSE_INVALID);
    }

    private static String rootType(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root == failure ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + "/" + root.getClass().getSimpleName();
    }
}
