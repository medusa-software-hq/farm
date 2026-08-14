-- One agent run within a session: the initial attempt (ordinal 0) and, later, fixup runs.
-- action_log holds the backend-neutral AgentRunLog as JSON; outcome and cost are the run's metadata.
-- JSON is kept in TEXT columns (not jsonb): the model is stored and read whole, with no DB-side JSON
-- queries yet. A later migration can promote these to jsonb if that changes.
CREATE TABLE session_run (
    id          TEXT        NOT NULL PRIMARY KEY,
    session_id  TEXT        NOT NULL REFERENCES sessions (id),
    ordinal     INTEGER     NOT NULL,
    action_log  TEXT        NOT NULL,
    outcome     TEXT        NOT NULL,
    cost        TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, ordinal)
);
