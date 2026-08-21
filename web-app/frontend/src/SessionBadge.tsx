import { Badge } from '@mantine/core';

const STATES: Record<string, { color: string; label: string }> = {
  RUNNING: { color: 'yellow', label: 'Running' },
  // Its own colour rather than a shade of running: this state is waiting on whoever reads it.
  AWAITING_REVIEW: { color: 'blue', label: 'Awaiting review' },
  COMPLETED: { color: 'fern', label: 'Completed' },
  FAILED: { color: 'red', label: 'Failed' },
};

/** Renders an issue's / session's state; an empty state reads as "Not started". */
export function SessionBadge({ state }: { state: string }) {
  const s = STATES[state];
  if (!s) {
    return (
      <Badge variant="light" color="gray" size="sm" radius="sm">
        Not started
      </Badge>
    );
  }
  return (
    <Badge variant="light" color={s.color} size="sm" radius="sm">
      {s.label}
    </Badge>
  );
}
