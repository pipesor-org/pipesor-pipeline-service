package org.pipesor.pipeline.execution.interfaces.rest.validation;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.pipesor.pipeline.configuration.OwnerValidationProperties;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup;
import org.pipesor.pipeline.execution.application.queryhandlers.ValidateOperationalReferences;
import org.pipesor.pipeline.execution.infrastructure.access.HttpIngestionScopeAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded read-only check for Correlation and Log Event. Callers and roles are enforced by the
 * platform route rules; this adapter enforces the wire contract, size, admission and deadline limits.
 * The body is read before admission, so slow uploads never hold a processing slot; the server
 * connection timeout bounds stalled bodies.
 */
@Hidden
@RestController
public class OperationalReferenceCheckController {
    public static final String PATH = "/internal/v1/evidence/operational-reference-checks";
    private static final Logger LOG = LoggerFactory.getLogger(OperationalReferenceCheckController.class);
    private final OwnerValidationProperties properties;
    private final OperationalReferenceLookup references;
    private final HttpIngestionScopeAuthority authority;
    private final Clock clock;
    private final Semaphore permits;

    public OperationalReferenceCheckController(OwnerValidationProperties properties, OperationalReferenceLookup references,
            HttpIngestionScopeAuthority authority, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.references = Objects.requireNonNull(references);
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
        this.permits = new Semaphore(properties.maxInFlight());
    }

    @PostMapping(PATH)
    public ResponseEntity<byte[]> check(HttpServletRequest request, @AuthenticationPrincipal Jwt caller) {
        long started = System.nanoTime();
        final String correlation;
        final Instant expires;
        final byte[] body;
        try {
            correlation = header(request, "X-Correlation-Id", true);
            if (!correlation.matches("[A-Za-z0-9._:-]{1,128}") || request.getQueryString() != null
                    || request.getHeader("Cookie") != null) throw new Rejected(400);
            var media = MediaType.parseMediaType(header(request, "Content-Type", true));
            if (!media.getType().equalsIgnoreCase("application") || !media.getSubtype().equalsIgnoreCase("json")
                    || media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset())) throw new Rejected(415);
            String encoding = header(request, "Content-Encoding", false);
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new Rejected(415);
            if (request.getContentLengthLong() > properties.maxRequestBytes()) throw new Rejected(413);
            expires = caller == null ? null : caller.getExpiresAt();
            if (expires == null || !expires.isAfter(clock.instant())) throw new Rejected(503);
            body = request.getInputStream().readNBytes(properties.maxRequestBytes() + 1);
            if (body.length > properties.maxRequestBytes()) throw new Rejected(413);
        } catch (Rejected rejected) {
            return problem(rejected.status);
        } catch (IOException unreadable) {
            LOG.warn("Operational reference check body unreadable: type={}", unreadable.getClass().getSimpleName());
            return problem(400);
        } catch (RuntimeException malformed) {
            return problem(400);
        }
        if (!permits.tryAcquire()) return problem(429);
        try {
            long budget = Math.min(properties.requestTimeout().toNanos(), Duration.between(clock.instant(), expires).toNanos());
            long elapsed = System.nanoTime() - started;
            if (budget - elapsed <= 0) return problem(503);
            var message = OwnerValidationJson.request(body, correlation);
            Instant now = clock.instant();
            Instant deadline = Collections.min(List.of(message.deadlineAt(), expires, now.plusNanos(budget - elapsed)));
            long remaining = Math.min(budget - elapsed, Duration.between(now, deadline).toNanos());
            if (remaining <= 0) return problem(503);
            long endNanos = System.nanoTime() + remaining;
            var handler = new ValidateOperationalReferences(query -> authority.verify(query, deadline, endNanos), references);
            var decision = handler.handle(message.query());
            if (!deadline.isAfter(clock.instant()) || endNanos - System.nanoTime() <= 0
                    || decision == ValidateOperationalReferences.Decision.UNAVAILABLE) {
                LOG.warn("Operational reference check unavailable: correlation={}", correlation);
                return problem(503);
            }
            var result = new LinkedHashMap<String, String>();
            result.put("requestId", message.requestId().toString());
            result.put("correlationId", correlation);
            result.put("decision", decision.name());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                    .body(OwnerValidationJson.JSON.writeValueAsBytes(result));
        } catch (IllegalArgumentException malformed) {
            return problem(400);
        } catch (RuntimeException unavailable) {
            LOG.warn("Operational reference check failed: correlation={}, type={}", correlation,
                    unavailable.getClass().getSimpleName());
            return problem(503);
        } finally {
            permits.release();
        }
    }

    private static String header(HttpServletRequest request, String name, boolean required) {
        var headers = Collections.list(request.getHeaders(name));
        if (headers.size() > 1 || required && headers.isEmpty()) throw new Rejected(400);
        if (headers.isEmpty()) return null;
        String result = headers.getFirst();
        if (result.isBlank() || result.length() > 512) throw new Rejected(400);
        return result;
    }

    static ResponseEntity<byte[]> problem(int status) {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST"; case 413 -> "REQUEST_TOO_LARGE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "OVERLOADED"; default -> "UNAVAILABLE";
        };
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(OwnerValidationJson.JSON.writeValueAsBytes(body));
    }

    private static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status) { super(null, null, false, false); this.status = status; }
    }
}
