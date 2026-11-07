package org.pipesor.pipeline.catalog.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.pipesor.pipeline.catalog.application.queries.PipelineActivityView;

public record PipelineActivitiesResource(List<PipelineActivityView> items, int offset, int limit,
        @Schema(description = "False: stored audit does not establish a complete historical timeline; missing fields are not reconstructed") boolean historyComplete) {
    public PipelineActivitiesResource { items = List.copyOf(items); }
}
