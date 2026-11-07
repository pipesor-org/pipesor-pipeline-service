package org.pipesor.pipeline.catalog.application.queries;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;

public record PipelineGrantView(UUID id, String principalRef, AccessCapability capability, Instant createdAt) {}
