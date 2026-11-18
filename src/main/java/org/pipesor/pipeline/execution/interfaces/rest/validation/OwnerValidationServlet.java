package org.pipesor.pipeline.execution.interfaces.rest.validation;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.pipesor.pipeline.configuration.OwnerValidationProperties;
import org.pipesor.pipeline.execution.application.ports.OperationalReferenceLookup;
import org.pipesor.pipeline.execution.application.queryhandlers.ValidateOperationalReferences;
import org.pipesor.pipeline.execution.infrastructure.access.HttpIngestionScopeAuthority;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Bounded read-only check. Timed-out work keeps its permit until it actually stops. */
public final class OwnerValidationServlet extends HttpServlet {
    public static final String PATH = "/internal/v1/evidence/operational-reference-checks";
    private final OwnerValidationProperties properties;
    private final OperationalReferenceLookup references;
    private final HttpIngestionScopeAuthority authority;
    private final Clock clock;
    private final Semaphore permits;
    private ScheduledThreadPoolExecutor deadlines;

    public OwnerValidationServlet(OwnerValidationProperties properties, OperationalReferenceLookup references,
            HttpIngestionScopeAuthority authority, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.authority = Objects.requireNonNull(authority);
        this.references = Objects.requireNonNull(references);
        this.clock = Objects.requireNonNull(clock);
        this.permits = new Semaphore(properties.maxInFlight());
    }

    @Override public void init() {
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "pipeline-owner-validation-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
    }
    @Override public void destroy() { if (deadlines != null) deadlines.shutdownNow(); }

    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"POST".equals(request.getMethod())) { problem(response, 403); return; }
        final String correlation;
        final Instant expires;
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
            if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authenticated)) throw new Rejected(401);
            expires = authenticated.getToken().getExpiresAt();
            if (expires == null || !expires.isAfter(clock.instant())) throw new Rejected(503);
        } catch (Rejected rejected) { problem(response, rejected.status); return; }
        catch (RuntimeException malformed) { problem(response, 400); return; }
        if (!permits.tryAcquire()) { problem(response, 429); return; }

        long started = System.nanoTime();
        final AsyncContext async;
        try { async = request.startAsync(); }
        catch (RuntimeException unavailable) { permits.release(); problem(response, 503); return; }
        var finished = new AtomicBoolean();
        // Reading can release immediately; work retains its slot through any response timeout.
        var processing = new AtomicBoolean();
        var released = new AtomicBoolean();
        var timer = new AtomicReference<ScheduledFuture<?>>();
        class Completion {
            void release() { if (released.compareAndSet(false, true)) permits.release(); }
            void finish(int status, String result) {
                if (System.nanoTime() - started >= properties.requestTimeout().toNanos()) { status = 503; result = null; }
                if (!finished.compareAndSet(false, true)) return;
                var scheduled = timer.get(); if (scheduled != null) scheduled.cancel(false);
                try {
                    if (status == 200) {
                        response.setStatus(200);
                        response.setContentType("application/json");
                        response.setHeader("Cache-Control", "no-store");
                        response.getOutputStream().write(result.getBytes(StandardCharsets.UTF_8));
                    } else problem(response, status);
                    response.flushBuffer();
                } catch (IOException | IllegalStateException closed) {
                    // The authenticated check may finish after the connection closes.
                } finally {
                    if (!processing.get()) release();
                    try { async.complete(); } catch (IllegalStateException closed) {}
                }
            }
        }
        var completion = new Completion();
        try {
            long tokenRemaining = Duration.between(clock.instant(), expires).toNanos();
            long budget = Math.min(properties.requestTimeout().toNanos(), tokenRemaining);
            if (budget <= 0) { completion.finish(503, null); return; }
            async.setTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget)));
            timer.set(deadlines.schedule(() -> completion.finish(503, null), budget, TimeUnit.NANOSECONDS));
            async.addListener(new AsyncListener() {
                public void onTimeout(AsyncEvent event) { completion.finish(503, null); }
                public void onError(AsyncEvent event) { completion.finish(503, null); }
                public void onComplete(AsyncEvent event) {}
                public void onStartAsync(AsyncEvent event) {}
            });
            var input = request.getInputStream();
            var bytes = new ByteArrayOutputStream();
            input.setReadListener(new ReadListener() {
                public void onDataAvailable() throws IOException {
                    var buffer = new byte[4096];
                    while (!finished.get() && input.isReady() && !input.isFinished()) {
                        int count = input.read(buffer);
                        if (count <= 0) return;
                        if (count > properties.maxRequestBytes() - bytes.size()) { completion.finish(413, null); return; }
                        bytes.write(buffer, 0, count);
                    }
                }
                public void onAllDataRead() {
                    if (finished.get()) return;
                    processing.set(true);
                    try {
                        if (finished.get()) return;
                        var message = OwnerValidationJson.request(bytes.toByteArray(), correlation);
                        long elapsed = System.nanoTime() - started;
                        Instant now = clock.instant();
                        Instant deadline = Collections.min(List.of(message.deadlineAt(), expires,
                                now.plusNanos(Math.max(0, budget - elapsed))));
                        long remaining = Math.min(budget - elapsed, Duration.between(now, deadline).toNanos());
                        if (remaining <= 0) throw new Rejected(503);
                        long endNanos = started + elapsed + remaining;
                        var previousTimer = timer.getAndSet(deadlines.schedule(
                                () -> completion.finish(503, null), remaining, TimeUnit.NANOSECONDS));
                        if (previousTimer != null) previousTimer.cancel(false);
                        if (finished.get()) return;
                        var handler = new ValidateOperationalReferences(
                                query -> authority.verify(query, deadline, endNanos), references);
                        var decision = handler.handle(message.query());
                        if (!deadline.isAfter(clock.instant()) || endNanos - System.nanoTime() <= 0
                                || decision == ValidateOperationalReferences.Decision.UNAVAILABLE) {
                            throw new Rejected(503);
                        }
                        completion.finish(200, OwnerValidationJson.JSON.writeValueAsString(Map.of(
                                "requestId", message.requestId().toString(), "correlationId", correlation,
                                "decision", decision.name())));
                    } catch (Rejected rejected) { completion.finish(rejected.status, null); }
                    catch (IllegalArgumentException malformed) { completion.finish(400, null); }
                    catch (RuntimeException unavailable) { completion.finish(503, null); }
                    finally { processing.set(false); completion.release(); }
                }
                public void onError(Throwable error) { completion.finish(503, null); }
            });
        } catch (RuntimeException | IOException unavailable) { completion.finish(503, null); }
    }

    private static String header(HttpServletRequest request, String name, boolean required) {
        var headers = Collections.list(request.getHeaders(name));
        if (headers.size() > 1 || required && headers.isEmpty()) throw new Rejected(400);
        if (headers.isEmpty()) return null;
        String result = headers.getFirst();
        if (result.isBlank() || result.length() > 512) throw new Rejected(400);
        return result;
    }
    public static void problem(HttpServletResponse response, int status) throws IOException {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST"; case 401 -> "UNAUTHENTICATED"; case 403 -> "CALLER_DENIED";
            case 413 -> "REQUEST_TOO_LARGE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE"; case 429 -> "OVERLOADED";
            default -> "UNAVAILABLE";
        };
        response.resetBuffer(); response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/problem+json");
        response.getOutputStream().write(OwnerValidationJson.JSON.writeValueAsBytes(Map.of("type", "about:blank",
                "title", HttpStatus.valueOf(status).getReasonPhrase(), "status", status, "code", code)));
    }
    private static final class Rejected extends RuntimeException {
        final int status;
        Rejected(int status) { super(null, null, false, false); this.status = status; }
    }
}
