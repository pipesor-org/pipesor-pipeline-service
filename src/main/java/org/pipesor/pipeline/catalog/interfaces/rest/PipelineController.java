package org.pipesor.pipeline.catalog.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.commands.ChangePipelineAccessCommand;
import org.pipesor.pipeline.catalog.application.queries.GetPipelineQuery;
import org.pipesor.pipeline.catalog.application.queries.ListVisiblePipelinesQuery;
import org.pipesor.pipeline.catalog.application.queries.ListRepositoryRegistrationsQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineAdministrationQuery;
import org.pipesor.pipeline.catalog.application.queries.PipelineGrantView;
import org.pipesor.pipeline.catalog.application.queries.PipelineView;
import org.pipesor.pipeline.catalog.application.queries.RepositoryRegistrationsView;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.application.services.PipelineAdministrationQueryService;
import org.pipesor.pipeline.catalog.application.services.PipelineConfigurationService;
import org.pipesor.pipeline.catalog.application.services.PipelineQueryService;
import org.pipesor.pipeline.catalog.application.services.PipelineRegistrationService;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.catalog.interfaces.rest.resources.PipelineActivitiesResource;
import org.pipesor.pipeline.catalog.interfaces.rest.resources.PipelineOwnershipResource;
import org.pipesor.pipeline.catalog.interfaces.rest.resources.PipelinePolicyResource;
import org.pipesor.pipeline.catalog.interfaces.rest.resources.PipelineSettingsResource;
import org.pipesor.pipeline.catalog.interfaces.rest.resources.RegisterPipelineResource;
import org.pipesor.pipeline.shared.interfaces.rest.CorrelationFilter;
import org.pipesor.pipeline.shared.interfaces.rest.PageResource;
import org.pipesor.pipeline.shared.interfaces.rest.RequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pipelines")
@SecurityRequirement(name = "bearerAuth")
public class PipelineController {
    private final PipelineQueryService queries;
    private final PipelineConfigurationService commands;
    private final HumanPipelineAccess access;
    private final PipelineRegistrationService registration;
    private final PipelineAdministrationQueryService administration;

    public PipelineController(PipelineQueryService queries, PipelineConfigurationService commands, HumanPipelineAccess access, PipelineRegistrationService registration, PipelineAdministrationQueryService administration) {
        this.queries = queries; this.commands = commands; this.access = access; this.registration = registration; this.administration = administration;
    }

    @PostMapping
    @Operation(summary = "Register an existing GitHub Actions workflow",
            description = "Selected IDs and repository administration are verified. Creates CONFIGURE for the creator; no OPERATE grant or backfill. Duplicates return 409.")
    @ApiResponse(responseCode = "201", description = "Monitored workflow registered",
            headers = @Header(name = "Location", description = "Relative URI of the registered pipeline", schema = @Schema(type = "string")))
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<PipelineView> register(@RequestBody RegisterPipelineResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        var created = registration.handle(resource.toCommand(RequestContext.actor(jwt), correlation));
        return ResponseEntity.created(URI.create("/api/v1/pipelines/" + created.id())).body(created);
    }

    @Operation(summary = "Replace monitoring policy",
            description = "All three keys are required; environment may be null to clear it. Leaves team references unchanged. ACTIVE/PAUSED controls AIOps monitoring, not GitHub Actions; no backfill.")
    @PutMapping("/{id}/policy")
    public PipelineView policy(@PathVariable UUID id, @RequestBody PipelinePolicyResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return commands.handle(resource.toCommand(id, RequestContext.actor(jwt), correlation));
    }

    @Operation(summary = "Replace responsible-team references",
            description = "Both keys are required and nullable. Explicit null clears the corresponding value. Leaves monitoring policy unchanged; does not define pipeline owners.")
    @PutMapping("/{id}/ownership")
    public PipelineView ownership(@PathVariable UUID id, @RequestBody PipelineOwnershipResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return commands.handle(resource.toCommand(id, RequestContext.actor(jwt), correlation));
    }

    @GetMapping
    public PageResource<PipelineView> list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int offset, @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit) {
        return new PageResource<>(queries.handle(new ListVisiblePipelinesQuery(RequestContext.actor(jwt), offset, limit)), offset, limit);
    }

    @GetMapping("/registrations")
    @Operation(summary = "Monitored workflows of a repository the caller can read on GitHub",
            description = "ADR-028: existence only, for anyone with current GitHub read access to the admitted repository, with or "
                    + "without a grant. accessible is true when the caller already has an effective capability on that pipeline. "
                    + "Ordered by numeric githubWorkflowId; no paging.")
    public RepositoryRegistrationsView registrations(
            @Parameter(description = "Numeric GitHub repository id", required = true, schema = @Schema(pattern = "^[1-9][0-9]{0,19}$"))
            @RequestParam String repositoryId, @AuthenticationPrincipal Jwt jwt) {
        return queries.handle(new ListRepositoryRegistrationsQuery(RequestContext.actor(jwt), repositoryId));
    }

    @GetMapping("/{id}")
    public PipelineView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var capabilities = access.require(RequestContext.actor(jwt), id, AccessCapability.VIEW);
        return queries.handle(new GetPipelineQuery(id)).orElseThrow(NoSuchElementException::new).withCapabilities(capabilities);
    }

    @GetMapping("/{id}/grants")
    public PageResource<PipelineGrantView> grants(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0")) @RequestParam(defaultValue = "0") int offset,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25")) @RequestParam(defaultValue = "25") int limit) {
        return new PageResource<>(administration.grants(new PipelineAdministrationQuery(
                id, RequestContext.actor(jwt), offset, limit)), offset, limit);
    }

    @GetMapping("/{id}/activities")
    public PipelineActivitiesResource activities(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0")) @RequestParam(defaultValue = "0") int offset,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25")) @RequestParam(defaultValue = "25") int limit) {
        return new PipelineActivitiesResource(administration.activities(new PipelineAdministrationQuery(
                id, RequestContext.actor(jwt), offset, limit)), offset, limit, false);
    }

    @PutMapping("/{id}/settings")
    public PipelineView configure(@PathVariable UUID id, @RequestBody PipelineSettingsResource resource,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        return commands.handle(resource.toCommand(id, RequestContext.actor(jwt), correlation));
    }

    @PutMapping("/{id}/grants/{principalRef}/{capability}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable UUID id, @PathVariable String principalRef, @PathVariable AccessCapability capability,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        commands.handle(new ChangePipelineAccessCommand(id, RequestContext.actor(jwt), correlation, RequestContext.principal(principalRef), capability, true));
    }

    @DeleteMapping("/{id}/grants/{principalRef}/{capability}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @PathVariable String principalRef, @PathVariable AccessCapability capability,
            @AuthenticationPrincipal Jwt jwt, @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlation) {
        commands.handle(new ChangePipelineAccessCommand(id, RequestContext.actor(jwt), correlation, RequestContext.principal(principalRef), capability, false));
    }
}
