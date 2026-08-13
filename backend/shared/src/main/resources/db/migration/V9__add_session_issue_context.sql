-- Record the issue a session works (repo + title as they were), so the sessions list and archive
-- stay self-describing after the issue closes or is renamed. Additive: NOT NULL with a default so
-- existing rows fill in. See README.md.
ALTER TABLE sessions ADD COLUMN repo_full_name TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT '';
