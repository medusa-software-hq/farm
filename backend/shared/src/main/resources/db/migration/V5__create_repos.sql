-- Repos the Farm app can reach, as a Farm-owned domain entity rather than a mirror: rows are never
-- deleted, only soft-orphaned (orphaned_at), so Farm history can hang off a stable repo row across
-- renames and reinstalls. Additive — see README.md.
CREATE TABLE repos (
    github_repo_id  BIGINT      NOT NULL,
    installation_id BIGINT      NOT NULL,
    full_name       TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    private         BOOLEAN     NOT NULL,
    default_branch  TEXT        NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    orphaned_at     TIMESTAMPTZ,
    PRIMARY KEY (installation_id, github_repo_id)
);
