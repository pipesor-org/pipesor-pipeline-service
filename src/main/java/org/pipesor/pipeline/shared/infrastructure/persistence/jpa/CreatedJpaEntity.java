package org.pipesor.pipeline.shared.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class CreatedJpaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CreatedJpaEntity() {
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
