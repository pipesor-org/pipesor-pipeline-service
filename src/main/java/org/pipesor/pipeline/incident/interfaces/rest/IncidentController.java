package org.pipesor.pipeline.incident.interfaces.rest;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.pipesor.pipeline.incident.application.queries.ListIncidentsQuery;
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

    public IncidentController(IncidentQueryService queries, IncidentLifecycleService commands, HumanPipelineAccess access, ExecutionQueryService executions) {
        this.queries = queries; this.commands = commands; this.access = access; this.executions = executions;
    }

    @GetMapping("/pipelines/{id}/incidents")
    public PageResource<IncidentView> list(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int offset, @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit) {
        access.require(RequestContext.actor(jwt), id, AccessCapability.VIEW);
        return new PageResource<>(queries.handle(new ListIncidentsQuery(id, offset, limit)), offset, limit);
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
