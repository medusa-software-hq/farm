-- Open issues on the Farm app's repos, as a Farm-owned domain entity rather than a mirror: rows are
-- never deleted, only soft-orphaned (orphaned_at) — an issue that closes or drops off the open list
-- keeps its row so a session and its history can hang off a stable issue across reopens. Keyed by
-- (github_repo_id, number): GitHub never reuses an issue number within a repo. Additive — see
-- README.md.
CREATE TABLE issues (
    github_repo_id  BIGINT      NOT NULL,
    number          INTEGER     NOT NULL,
    installation_id BIGINT      NOT NULL,
    repo_full_name  TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    orphaned_at     TIMESTAMPTZ,
    PRIMARY KEY (github_repo_id, number)
);
