-- A processing session: one run of Farm working an issue. Anemic for now — it records that the
-- issue was picked up and whether that run finished — but it is the row real work (AI steps, PRs,
-- status) will hang off later. Keyed by an opaque id; the issue it belongs to is (github_repo_id,
-- number). Additive — see README.md.
CREATE TABLE sessions (
    id              TEXT        NOT NULL PRIMARY KEY,
    installation_id BIGINT      NOT NULL,
    github_repo_id  BIGINT      NOT NULL,
    number          INTEGER     NOT NULL,
    state           TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
