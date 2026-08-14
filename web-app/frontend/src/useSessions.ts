import { useEffect, useState } from 'react';
import { farm } from './api.ts';
import { useOrg } from './OrgContext.tsx';
import { useApiHeaders } from './useApiHeaders.ts';

export interface Session {
  id: string;
  repoFullName: string;
  number: number;
  title: string;
  state: string;
  startedAtMillis: number;
  finishedAtMillis: number;
  pullRequestUrl: string;
}

/**
 * The sessions for the selected org, newest first. `null` while first loading. Pass `pollMs` to keep
 * it live (so a running session's state updates on its own).
 */
export function useSessions(pollMs?: number): Session[] | null {
  const headers = useApiHeaders();
  const { selected } = useOrg();
  const [all, setAll] = useState<Session[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const res = await farm.listSessions({}, { headers });
        if (!cancelled) {
          setAll(
            res.sessions.map((s) => ({
              id: s.id,
              repoFullName: s.repoFullName,
              number: s.number,
              title: s.title,
              state: s.state,
              startedAtMillis: Number(s.startedAtMillis),
              finishedAtMillis: Number(s.finishedAtMillis),
              pullRequestUrl: s.pullRequestUrl,
            }))
          );
        }
      } catch {
        if (!cancelled) {
          setAll([]);
        }
      }
    }
    void load();
    const interval = pollMs ? setInterval(() => void load(), pollMs) : undefined;
    return () => {
      cancelled = true;
      if (interval) {
        clearInterval(interval);
      }
    };
  }, [headers, pollMs]);

  const prefix = selected ? `${selected.orgLogin}/` : null;
  return all === null
    ? null
    : all.filter((s) => prefix !== null && s.repoFullName.startsWith(prefix));
}
