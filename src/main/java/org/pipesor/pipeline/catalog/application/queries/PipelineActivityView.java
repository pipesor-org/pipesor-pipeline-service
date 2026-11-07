package org.pipesor.pipeline.catalog.application.queries;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PipelineActivityView(UUID id, String action, String actorRef, Instant occurredAt,
                                   String correlationId, Map<String, Object> beforeState, Map<String, Object> afterState) {}
