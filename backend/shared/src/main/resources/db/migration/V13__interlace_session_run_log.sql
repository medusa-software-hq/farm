-- AgentRunLog became an ordered list of entries (a step or a warning) rather than a list of steps,
-- so that a run reads as one account of what happened. Rows written before that carry {"steps": [...]}
-- with untagged elements, which no longer decodes; each element becomes a tagged step entry, in
-- place and in order.
UPDATE session_run
SET action_log = jsonb_build_object(
        'entries',
        COALESCE(
            (
                SELECT jsonb_agg(
                           jsonb_build_object('type', 'software.medusa.farm.shared.AgentStep') || step
                           ORDER BY position
                       )
                FROM jsonb_array_elements((action_log::jsonb) -> 'steps')
                     WITH ORDINALITY AS element(step, position)
            ),
            '[]'::jsonb
        )
    )::text
WHERE (action_log::jsonb) ? 'steps';
