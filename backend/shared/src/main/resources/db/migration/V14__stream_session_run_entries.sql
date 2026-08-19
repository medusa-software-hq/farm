-- A run is now recorded while it happens rather than once it is over, so its entries move out of the
-- action_log blob into rows that can be appended to one at a time, and its outcome and summary
-- become absent-until-known rather than required at insert.
ALTER TABLE session_run ALTER COLUMN outcome DROP NOT NULL;
ALTER TABLE session_run ALTER COLUMN summary DROP NOT NULL;

-- Keyed by the run's own (session_id, ordinal) rather than by session_run.id, which is how a run is
-- addressed everywhere else; position counts from zero and orders the entries within a run.
CREATE TABLE session_run_entry (
    session_id TEXT    NOT NULL,
    ordinal    INTEGER NOT NULL,
    position   INTEGER NOT NULL,
    entry      TEXT    NOT NULL,
    PRIMARY KEY (session_id, ordinal, position),
    FOREIGN KEY (session_id, ordinal) REFERENCES session_run (session_id, ordinal) ON DELETE CASCADE
);

INSERT INTO session_run_entry (session_id, ordinal, position, entry)
SELECT run.session_id, run.ordinal, element.position - 1, element.entry::text
FROM session_run AS run,
     LATERAL jsonb_array_elements((run.action_log::jsonb) -> 'entries')
         WITH ORDINALITY AS element(entry, position);

ALTER TABLE session_run DROP COLUMN action_log;
