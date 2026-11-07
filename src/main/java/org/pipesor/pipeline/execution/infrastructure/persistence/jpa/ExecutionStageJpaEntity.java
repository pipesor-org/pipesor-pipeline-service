package org.pipesor.pipeline.execution.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.pipesor.pipeline.shared.domain.model.valueobjects.PipelineStage;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "execution_stages", schema = "ops")
public class ExecutionStageJpaEntity extends CreatedJpaEntity {

    @Column(nullable = false)
    UUID executionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"PipelineStage\"")
    PipelineStage stage;

    @Column(nullable = true, columnDefinition = "text")
    String externalStatus;

    @Column(nullable = true)
    Instant startedAt;

    @Column(nullable = true)
    Instant completedAt;

    @Column(nullable = false, columnDefinition = "text")
    String correlationId;

    protected ExecutionStageJpaEntity() {
    }
}
