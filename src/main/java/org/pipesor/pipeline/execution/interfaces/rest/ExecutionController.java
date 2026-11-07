package org.pipesor.pipeline.execution.interfaces.rest;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.pipesor.pipeline.catalog.application.services.HumanPipelineAccess;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.execution.application.queries.ExecutionStageView;
import org.pipesor.pipeline.execution.application.queries.ExecutionView;
import org.pipesor.pipeline.execution.application.queries.GetExecutionQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionStagesQuery;
import org.pipesor.pipeline.execution.application.queries.ListExecutionsQuery;
import org.pipesor.pipeline.execution.application.services.ExecutionQueryService;
import org.pipesor.pipeline.shared.interfaces.rest.PageResource;
import org.pipesor.pipeline.shared.interfaces.rest.RequestContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class ExecutionController {
    private final ExecutionQueryService queries;
    private final HumanPipelineAccess access;

    public ExecutionController(ExecutionQueryService queries, HumanPipelineAccess access) {
        this.queries = queries; this.access = access;
    }

    @GetMapping("/pipelines/{id}/executions")
    public PageResource<ExecutionView> list(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int offset, @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit) {
        access.require(RequestContext.actor(jwt), id, AccessCapability.VIEW);
        return new PageResource<>(queries.handle(new ListExecutionsQuery(id, offset, limit)), offset, limit);
    }

    @GetMapping("/executions/{id}")
    public ExecutionView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var execution = queries.handle(new GetExecutionQuery(id)).orElseThrow(NoSuchElementException::new);
        access.require(RequestContext.actor(jwt), execution.pipelineId(), AccessCapability.VIEW);
        return execution;
    }

    @GetMapping("/executions/{id}/stages")
    public List<ExecutionStageView> stages(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        get(id, jwt);
        return queries.handle(new ListExecutionStagesQuery(id));
    }
}
