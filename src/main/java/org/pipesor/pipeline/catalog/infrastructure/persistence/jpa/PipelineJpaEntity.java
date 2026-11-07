package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.MonitoringStatus;
import org.pipesor.pipeline.catalog.domain.model.valueobjects.PipelineCriticality;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "pipelines", schema = "ops")
public class PipelineJpaEntity extends CreatedJpaEntity {

    @Column(nullable = false)
    UUID repositoryId;

    @Column(nullable = false, columnDefinition = "text")
    String githubWorkflowId;

    @Column(nullable = false, columnDefinition = "text")
    String workflowName;

    @Column(nullable = false, columnDefinition = "text")
    String workflowFile;

    @Column(nullable = true, columnDefinition = "text")
    String environment;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"PipelineCriticality\"")
    PipelineCriticality criticality;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ops.\"MonitoringStatus\"")
    MonitoringStatus monitoringStatus;

    @Column(nullable = true, columnDefinition = "text")
    String responsibleTeamRef;

    @Column(nullable = true, columnDefinition = "text")
    String responsibleTeamNameSnapshot;

    protected PipelineJpaEntity() {
    }
}
