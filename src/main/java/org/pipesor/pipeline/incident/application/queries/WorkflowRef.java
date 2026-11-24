package org.pipesor.pipeline.incident.application.queries;

import io.swagger.v3.oas.annotations.media.Schema;

/** The incident's monitored GitHub Actions workflow: numeric GitHub id (as a string) and name. */
public record WorkflowRef(
        @Schema(description = "GitHub numeric workflow id", pattern = "^[1-9][0-9]*$", example = "926001") String workflowId,
        @Schema(example = "CI") String name) {
}
