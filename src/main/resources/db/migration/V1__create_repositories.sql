CREATE TABLE ops.repositories (
    id uuid PRIMARY KEY,
    github_repository_id text NOT NULL UNIQUE,
    full_name text NOT NULL,
    default_branch text,
    created_at timestamptz NOT NULL
);
