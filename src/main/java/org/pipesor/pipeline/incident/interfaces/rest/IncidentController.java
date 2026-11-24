package org.pipesor.pipeline.incident.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.execution.application.queries.ExecutionPipelinesQuery;
import org.pipesor.pipeline.execution.application.services.ExecutionQueryService;
import org.pipesor.pipeline.incident.application.commands.TransitionIncidentCommand;
import org.pipesor.pipeline.incident.application.queries.GetIncidentQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentRunView;
import org.pipesor.pipeline.incident.application.queries.IncidentView;
import org.pipesor.pipeline.incident.application.queries.ListIncidentRunsQuery;
import org.pipesor.pipeline.incident.application.queries.IncidentSummaryQuery;
import org.pipesor.pipeline.incident.application.queries.ListIncidentQueueQuery;
import org.pipesor.pipeline.incident.application.queries.ListIncidentsQuery;
import org.pipesor.pipeline.incident.application.services.IncidentQueueService;
import org.pipesor.pipeline.incident.application.services.IncidentSummaryService;
import org.pipesor.pipeline.incident.interfaces.rest.resources.AppliedFiltersResource;
import org.pipesor.pipeline.incident.interfaces.rest.resources.IncidentSummaryResource;
import org.pipesor.pipeline.incident.interfaces.rest.transform.IncidentFilterParameters;
import org.pipesor.pipeline.incident.interfaces.rest.resources.IncidentQueueResource;
import org.pipesor.pipeline.incident.interfaces.rest.transform.IncidentQueuePageTokens;
import org.pipesor.pipeline.incident.application.services.IncidentLifecycleService;
import org.pipesor.pipeline.incident.application.services.IncidentQueryService;
import org.pipesor.pipeline.incident.domain.model.valueobjects.IncidentAction;
import org.pipesor.pipeline.incident.interfaces.rest.resources.VerificationResource;
import org.pipesor.pipeline.shared.interfaces.rest.CorrelationFilter;
import org.pipesor.pipeline.shared.interfaces.rest.PageResource;
import org.pipesor.pipeline.shared.interfaces.rest.RequestContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class IncidentController {
    private final IncidentQueryService queries;
    private final IncidentLifecycleService commands;
    private final HumanPipelineAccess access;
    private final ExecutionQueryService executions;
    private final IncidentQueueService queue;
    private final IncidentSummaryService summaries;

    public IncidentController(IncidentQueryService queries, IncidentLifecycleService commands, HumanPipelineAccess access,
            ExecutionQueryService executions, IncidentQueueService queue, IncidentSummaryService summaries) {
        this.queries = queries; this.commands = commands; this.access = access; this.executions = executions; this.queue = queue;
        this.summaries = summaries;
    }

    @GetMapping("/pipelines/{id}/incidents")
    public PageResource<IncidentView> list(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int offset, @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit) {
        access.require(RequestContext.actor(jwt), id, AccessCapability.VIEW);
        return new PageResource<>(queries.handle(new ListIncidentsQuery(id, offset, limit)), offset, limit);
    }

    /** Filter parameters are declared in the OpenAPI document by {@code OpenApiConfiguration} (ADR-027). */
    @GetMapping("/incidents")
    @Operation(summary = "Cross-pipeline incident queue with filters",
            description = "Filtered by VIEW grants and current GitHub access, then by the optional filters, before paging. "
                    + "Values of one filter are OR-ed, different filters AND-ed. total counts every match the caller may view.")
    public IncidentQueueResource queue(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @Parameter(required = true) @RequestParam ListIncidentQueueQuery.State state,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "24"))
            @RequestParam(defaultValue = "24") int pageSize,
            @Parameter(description = "Opaque token from nextPageToken, valid only with the same state, filters and sort")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Order, newest first: createdAt (default) or, with state=RESOLVED only, resolvedAt",
                    schema = @Schema(type = "string", allowableValues = {"createdAt", "resolvedAt"}, defaultValue = "createdAt"))
            @RequestParam(required = false) String sort) {
        var filter = IncidentFilterParameters.parse(state, request);
        var order = ListIncidentQueueQuery.Sort.parse(sort);
        var page = queue.handle(new ListIncidentQueueQuery(RequestContext.actor(jwt), filter, order, pageSize,
                IncidentQueuePageTokens.decode(pageToken, filter, order)));
        return new IncidentQueueResource(page.items(), IncidentQueuePageTokens.encode(page.next(), filter, order), page.total(),
                AppliedFiltersResource.of(filter, order));
    }

    @GetMapping("/incidents/summary")
    @Operation(summary = "Counts of the filtered incident queue per severity, status and diagnosis",
            description = "Same authorization and filters as GET /api/v1/incidents; every count map sums to total.")
    public IncidentSummaryResource summary(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            @Parameter(required = true) @RequestParam ListIncidentQueueQuery.State state) {
        var filter = IncidentFilterParameters.parse(state, request);
        return IncidentSummaryResource.of(summaries.handle(new IncidentSummaryQuery(RequestContext.actor(jwt), filter)), filter);
    }

    @GetMapping("/incidents/{id}")
    public IncidentView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var incident = queries.handle(new GetIncidentQuery(id)).orElseThrow(NoSuchElementException::new);
        access.require(RequestContext.actor(jwt), incident.pipelineId(), AccessCapability.VIEW);
        return incident;
    }

    @GetMapping("/incidents/{id}/runs")
    public PageResource<IncidentRunView> runs(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int offset, @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit) {
        var incident = get(id, jwt);
        var runs = queries.handle(new ListIncidentRunsQuery(id, offset, limit));
        var ids = runs.stream().map(IncidentRunView::executionId).collect(Collectors.toSet());
        var owners = executions.handle(new ExecutionPipelinesQuery(ids));
        if (!owners.keySet().equals(ids)) { throw new NoSuchElementException("Referenced execution absent"); }
        for (UUID pipeline : new HashSet<>(owners.values())) {
            if (!pipeline.equals(incident.pipelineId())) { access.require(RequestContext.actor(jwt), pipeline, AccessCapability.VIEW); }
        }
        return new PageResource<>(runs, offset, limit);
    }

    @PostMapping("/incidents/{id}/remediation")
    public IncidentView remediate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return transition(id, IncidentAction.START_REMEDIATION, null, jwt, correlation);
    }

    @PostMapping("/incidents/{id}/verification")
    public IncidentView verify(@PathVariable UUID id, @RequestBody VerificationResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return transition(id, IncidentAction.START_VERIFICATION, resource.executionId(), jwt, correlation);
    }

    @PostMapping("/incidents/{id}/retry-remediation")
    public IncidentView retry(@PathVariable UUID id, @RequestBody VerificationResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return transition(id, IncidentAction.RETRY_REMEDIATION, resource.executionId(), jwt, correlation);
    }

    @PostMapping("/incidents/{id}/resolve")
    public IncidentView resolve(@PathVariable UUID id, @RequestBody VerificationResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return transition(id, IncidentAction.RESOLVE, resource.executionId(), jwt, correlation);
    }

    private IncidentView transition(UUID id, IncidentAction action, UUID execution, Jwt jwt, String correlation) {
        commands.handle(new TransitionIncidentCommand(id, action, execution, RequestContext.actor(jwt), correlation));
        return queries.handle(new GetIncidentQuery(id)).orElseThrow(NoSuchElementException::new);
    }
}
