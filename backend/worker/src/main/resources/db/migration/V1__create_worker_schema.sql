-- Farm worker domain store + read model. Owned by Flyway at runtime; the SQLDelight `.sq`
-- CREATE TABLEs mirror this file for compile-time query verification only.

CREATE TABLE pipeline (
    repo_full_name   TEXT    NOT NULL,
    issue_number     INTEGER NOT NULL,
    stage            TEXT    NOT NULL,
    pr_number        INTEGER,
    pr_url           TEXT,
    merge_commit_sha TEXT,
    failure_summary  TEXT,
    updated_at       TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (repo_full_name, issue_number)
);

CREATE TABLE stage_transition (
    id             BIGSERIAL PRIMARY KEY,
    repo_full_name TEXT      NOT NULL,
    issue_number   INTEGER   NOT NULL,
    stage          TEXT      NOT NULL,
    at             TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE session (
    id                TEXT    NOT NULL PRIMARY KEY,
    repo_full_name    TEXT    NOT NULL,
    issue_number      INTEGER NOT NULL,
    engine_session_id TEXT,
    outcome_kind      TEXT    NOT NULL,
    transcript_uri    TEXT,
    total_cost_usd    DOUBLE PRECISION,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
