package org.pipesor.pipeline.incident.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.pipesor.pipeline.incident.application.queries.InsightsQuery;
import org.pipesor.pipeline.incident.application.queries.InvalidIncidentFilter;
import org.pipesor.pipeline.incident.application.services.InsightsService;
import org.pipesor.pipeline.incident.interfaces.rest.resources.InsightsResource;
import org.pipesor.pipeline.shared.interfaces.rest.RequestContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** History Insights computed in the server over the caller's visible resolved incidents (ADR-027). */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class InsightsController {
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_TOP = 10;
    private final InsightsService insights;
    private final Clock clock;

    public InsightsController(InsightsService insights, Clock clock) {
        this.insights = insights;
        this.clock = clock;
    }

    @GetMapping("/insights")
    @Operation(summary = "History Insights over visible resolved incidents",
            description = "Population: resolved incidents the caller may view with resolvedAt in [from, to]. Defaults: to = now, "
                    + "from = start of the UTC day 29 days before to. The window may not exceed 366 days.")
    public InsightsResource insights(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "RFC 3339 instant", schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String from,
            @Parameter(description = "RFC 3339 instant", schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) String to,
            @Parameter(description = "Maximum recurring contexts", schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50", defaultValue = "10"))
            @RequestParam(required = false) String top) {
        Instant end = to == null ? clock.instant().truncatedTo(ChronoUnit.MILLIS) : instant(to);
        Instant start = from == null
                ? end.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant().minus(Duration.ofDays(DEFAULT_DAYS - 1))
                : instant(from);
        return InsightsResource.of(insights.handle(new InsightsQuery(RequestContext.actor(jwt), start, end, top(top))));
    }

    private static Instant instant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new InvalidIncidentFilter("Invalid instant");
        }
    }

    private static int top(String value) {
        if (value == null) return DEFAULT_TOP;
        if (!value.matches("[0-9]{1,3}")) throw new InvalidIncidentFilter("Invalid top");
        return Integer.parseInt(value);
    }
}
