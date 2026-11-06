package org.pipesor.pipeline.catalog.domain.model.entities;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.pipeline.shared.domain.model.Entity;

public final class GitHubRepository extends Entity {

    private final String githubRepositoryId;
    private final String fullName;
    private final String defaultBranch;
    private final Instant createdAt;

    public GitHubRepository(UUID id, String githubRepositoryId, String fullName,
                            String defaultBranch, Instant createdAt) {
        super(id);
        this.githubRepositoryId = Objects.requireNonNull(githubRepositoryId, "githubRepositoryId is required");
        this.fullName = Objects.requireNonNull(fullName, "fullName is required");
        this.defaultBranch = defaultBranch;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
