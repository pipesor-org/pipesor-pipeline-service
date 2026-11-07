package org.pipesor.pipeline.catalog.application.ports;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;

/** Local AIOps grants only. Human entry points also require HumanPipelineAccess before write transactions. */
public interface PipelineAccess {
    Map<UUID, Set<AccessCapability>> capabilities(String actorRef, Set<UUID> pipelines);
    void require(String actorRef, UUID pipelineId, AccessCapability capability);
}
