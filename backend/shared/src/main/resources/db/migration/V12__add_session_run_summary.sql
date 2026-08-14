-- A cheap-model distillation of the run's actions, used to orient a later fixup run. Null when
-- summarization was unavailable (the run's actions are still recorded either way).
ALTER TABLE session_run ADD COLUMN summary TEXT;
