package org.pipesor.pipeline.catalog.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.pipesor.pipeline.shared.infrastructure.persistence.jpa.CreatedJpaEntity;

@Entity
@Table(name = "repositories", schema = "ops")
public class GitHubRepositoryJpaEntity extends CreatedJpaEntity {

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String githubRepositoryId;

    @Column(nullable = false, columnDefinition = "text")
    private String fullName;

    @Column(columnDefinition = "text")
    private String defaultBranch;

    protected GitHubRepositoryJpaEntity() {
    }

    public String getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }
}
