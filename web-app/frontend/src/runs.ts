import { farm } from './api.ts';

/** One thing the agent did, or one thing its backend warned about, in the order it happened. */
export type RunEntry =
  | { kind: 'step'; text: string; actions: string[] }
  | { kind: 'warning'; text: string };

export type RunAttempt = {
  number: number;
  /** Abandoned means it stopped without closing and something else was tried after it. */
  state: 'running' | 'abandoned' | 'finished';
  entries: RunEntry[];
  summary: string;
  costUsd: number;
};

export type Run = { ordinal: number; attempts: RunAttempt[] };

const ATTEMPT_STATES: Record<string, RunAttempt['state']> = {
  RUNNING: 'running',
  ABANDONED: 'abandoned',
  FINISHED: 'finished',
};

/** Renders one tool action as the phrase the timeline shows. */
function describe(action: { action: { case: string | undefined; value?: unknown } }): string {
  const { case: which, value } = action.action;
  switch (which) {
    case 'editedPath':
      return `edited ${String(value)}`;
    case 'readPath':
      return `read ${String(value)}`;
    case 'command':
      return `ran ${String(value)}`;
    case 'query':
      return `searched ${String(value)}`;
    default:
      return `used ${String(value)}`;
  }
}

export async function fetchRuns(sessionId: string, headers: HeadersInit): Promise<Run[]> {
  const res = await farm.getSessionRuns({ sessionId }, { headers });
  return res.runs.map((run) => ({
    ordinal: run.ordinal,
    attempts: run.attempts.map((attempt) => ({
      number: attempt.number,
      state: ATTEMPT_STATES[attempt.state] ?? 'running',
      summary: attempt.outcome?.summary ?? '',
      costUsd: attempt.outcome?.cost?.usd ?? 0,
      entries: attempt.entries.flatMap((entry): RunEntry[] => {
        if (entry.entry.case === 'warning') {
          return [{ kind: 'warning', text: entry.entry.value }];
        }
        if (entry.entry.case === 'step') {
          const step = entry.entry.value;
          return [{ kind: 'step', text: step.text, actions: step.toolActions.map(describe) }];
        }
        return [];
      }),
    })),
  }));
}
