-- A run is now recorded while it happens rather than once it is over, and every try at it is kept
-- rather than overwritten: a crashed try is worth reading back in an internal tool, even though
-- Temporal treats its retries as invisible. The attempt number is the activity attempt, so a try
-- here and a try in the workflow's history are the same thing.
--
-- session_run goes: a run is identified by (session_id, ordinal), and held nothing an attempt does
-- not. Rows recorded before this become attempt 1, which is what they were.
CREATE TABLE session_run_attempt (
    session_id TEXT        NOT NULL REFERENCES sessions (id),
    ordinal    INTEGER     NOT NULL,
    attempt    INTEGER     NOT NULL,
    outcome    TEXT,
    cost       TEXT,
    summary    TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, ordinal, attempt)
);

CREATE TABLE session_run_entry (
    session_id TEXT    NOT NULL,
    ordinal    INTEGER NOT NULL,
    attempt    INTEGER NOT NULL,
    position   INTEGER NOT NULL,
    entry      TEXT    NOT NULL,
    PRIMARY KEY (session_id, ordinal, attempt, position),
    FOREIGN KEY (session_id, ordinal, attempt)
        REFERENCES session_run_attempt (session_id, ordinal, attempt) ON DELETE CASCADE
);

INSERT INTO session_run_attempt (session_id, ordinal, attempt, outcome, cost, summary, started_at)
SELECT session_id, ordinal, 1, outcome, cost, summary, created_at
FROM session_run;

INSERT INTO session_run_entry (session_id, ordinal, attempt, position, entry)
SELECT run.session_id, run.ordinal, 1, element.position - 1, element.entry::text
FROM session_run AS run,
     LATERAL jsonb_array_elements((run.action_log::jsonb) -> 'entries')
         WITH ORDINALITY AS element(entry, position);

DROP TABLE session_run;
