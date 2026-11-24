package org.pipesor.pipeline.incident.application.queries;

import io.swagger.v3.oas.annotations.media.Schema;

/** The incident's GitHub repository: numeric GitHub id (as a string) and current full name. */
public record RepositoryRef(
        @Schema(description = "GitHub numeric repository id", pattern = "^[1-9][0-9]*$", example = "825001") String repositoryId,
        @Schema(example = "owner/name") String fullName) {
}
