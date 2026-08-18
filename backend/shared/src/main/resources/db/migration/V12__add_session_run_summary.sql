-- A cheap-model distillation of the run's actions, used to orient a later fixup run. Required: a run
-- is not recorded at all until it has been summarized, so there is no run without one.
-- Safe as a plain NOT NULL add: nothing writes session_run before this migration ships.
ALTER TABLE session_run ADD COLUMN summary TEXT NOT NULL;
