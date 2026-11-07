package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.AccessCapability;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "pipeline_access_grants", schema = "ops")
public class PipelineAccessGrantJpaEntity extends CreatedJpaEntity {
    @Column(nullable = false) UUID pipelineId;
    @Column(nullable = false, columnDefinition = "text") String principalRef;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"AccessCapability\"") AccessCapability capability;
    @Column(nullable = false, columnDefinition = "text") String correlationId;
    protected PipelineAccessGrantJpaEntity() {}
}
