package org.pipesor.pipeline.execution.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "pipeline_executions", schema = "ops")
public class ExecutionJpaEntity extends CreatedJpaEntity {

    @Column(nullable = false)
    UUID pipelineId;

    @Column(nullable = false, columnDefinition = "text")
    String githubRunId;

    @Column(nullable = false)
    int runAttempt;

    @Column(nullable = false, columnDefinition = "text")
    String branch;

    @Column(nullable = false, columnDefinition = "text")
    String commitSha;

    @Column(nullable = false, columnDefinition = "text")
    String externalStatus;

    @Column(nullable = true, columnDefinition = "text")
    String externalConclusion;

    @Column(nullable = false)
    boolean failed;

    @Column(nullable = true)
    Instant startedAt;

    @Column(nullable = true)
    Instant completedAt;

    @Column(nullable = false, columnDefinition = "text")
    String correlationId;

    protected ExecutionJpaEntity() {
    }
}
