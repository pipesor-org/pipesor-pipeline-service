package org.pipesor.pipeline.shared.domain.model;

import java.util.Objects;
import java.util.UUID;

public abstract class Entity {

    private final UUID id;

    protected Entity(UUID id) {
        this.id = Objects.requireNonNull(id, "id is required");
    }

    public UUID getId() {
        return id;
    }
}
