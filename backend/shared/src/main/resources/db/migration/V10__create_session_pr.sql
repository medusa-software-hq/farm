-- The pull request a session opened. Separate 1:1-optional table rather than columns on `sessions`,
-- so a session with no PR is simply the absence of a row. merged_at is filled by the merge gate.
CREATE TABLE session_pr (
    session_id TEXT        NOT NULL PRIMARY KEY REFERENCES sessions (id),
    pr_number  INTEGER     NOT NULL,
    pr_url     TEXT        NOT NULL,
    head_sha   TEXT        NOT NULL,
    merged_at  TIMESTAMPTZ
);
