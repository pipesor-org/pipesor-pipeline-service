package org.pipesor.pipeline.catalog.application.ports;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** In-process needs only. The Ingestion adapter maps it to the /internal/v1/github/** wire contract. */
public interface GitHubConnector {
    Access verifyAccess(AccessRequest request);
    Workflow verifyWorkflow(String actorRef, String repositoryId, String workflowId);

    enum Operation { VIEW, OPERATE, CONFIGURE, REGISTER, GRANT_VIEW, GRANT_OPERATE, GRANT_CONFIGURE }

    /** Actor/subject are {@code tenant:object} references derived from verified platform session tokens. */
    record AccessRequest(String actorRef, String subjectRef, Set<String> repositoryIds, Operation operation) {
        public AccessRequest { repositoryIds = Set.copyOf(repositoryIds); }
    }
    /** readable means current effective user AND App access, active installation and required SSO;
     * admittedForTenant covers the installation/account allowlist for the bound Entra tenant. */
    record RepositoryAccess(String repositoryId, String installationId, String accountId, boolean admittedForTenant,
                            boolean readable, boolean administrator) {
        public RepositoryAccess(String repositoryId, String installationId, boolean admittedForTenant,
                                boolean readable, boolean administrator) {
            this(repositoryId, installationId, null, admittedForTenant, readable, administrator);
        }
    }
    record Access(String actorRef, String subjectRef, Operation operation, String githubUserId,
                  boolean linked, Instant verifiedAt, Instant validUntil, Map<String, RepositoryAccess> repositories) {
        public Access { repositories = Map.copyOf(repositories); }
    }
    record Workflow(Access access, String repositoryId, String workflowId, String fullName,
                    String defaultBranch, String workflowName, String workflowFile) {}
}
