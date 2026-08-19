import { Anchor, Card, Grid, Group, Loader, Stack, Text, Timeline, Title } from '@mantine/core';
import { type ReactNode, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { farm } from '../api.ts';
import { IconActivity, IconArrowLeft, IconCheck, IconSprout, IconX } from '../icons.tsx';
import { type Run, type RunAttempt, fetchRuns } from '../runs.ts';
import { SessionBadge } from '../SessionBadge.tsx';
import { clockTime, duration, relativeTime } from '../time.ts';
import { useApiHeaders } from '../useApiHeaders.ts';
import { type Session } from '../useSessions.ts';

type Loaded = { kind: 'loading' } | { kind: 'missing' } | { kind: 'ok'; session: Session };

function MetaRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group justify="space-between" gap="md" wrap="nowrap">
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <Text size="sm" fw={520} ta="right">
        {children}
      </Text>
    </Group>
  );
}

export function SessionDetail() {
  const { id } = useParams();
  const headers = useApiHeaders();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [runs, setRuns] = useState<Run[]>([]);

  useEffect(() => {
    if (!id) {
      return;
    }
    let cancelled = false;
    let interval: ReturnType<typeof setInterval> | undefined;

    function stopPolling() {
      if (interval !== undefined) {
        clearInterval(interval);
        interval = undefined;
      }
    }

    async function load() {
      try {
        const res = await farm.getSession({ id: id ?? '' }, { headers });
        const s = res.session;
        if (cancelled || !s) {
          return;
        }
        setRuns(await fetchRuns(s.id, headers));
        setLoaded({
          kind: 'ok',
          session: {
            id: s.id,
            repoFullName: s.repoFullName,
            number: s.number,
            title: s.title,
            state: s.state,
            startedAtMillis: Number(s.startedAtMillis),
            finishedAtMillis: Number(s.finishedAtMillis),
            pullRequestUrl: s.pullRequestUrl,
          },
        });
        // A session that has finished will not change again, and its runs are the larger of the
        // two reads and grow with the work the agent did.
        if (s.state !== 'RUNNING') {
          stopPolling();
        }
      } catch {
        if (!cancelled) {
          setLoaded({ kind: 'missing' });
        }
      }
    }
    void load();
    // Assigned before the first load can reach its state check — that awaits the network, while
    // this runs synchronously — so a session that is already finished stops the loop on its own
    // first pass rather than polling once more.
    interval = setInterval(() => void load(), 2000);
    return () => {
      cancelled = true;
      stopPolling();
    };
  }, [id, headers]);

  return (
    <Stack gap="lg">
      <Anchor component={Link} to="/sessions" c="dimmed" size="sm">
        <Group gap={4} component="span">
          <IconArrowLeft size={14} /> Sessions
        </Group>
      </Anchor>

      {loaded.kind === 'loading' ? (
        <Loader size="sm" color="fern" />
      ) : loaded.kind === 'missing' ? (
        <Text c="dimmed" size="sm">
          That session could not be found.
        </Text>
      ) : (
        <Detail session={loaded.session} runs={runs} />
      )}
    </Stack>
  );
}

function Detail({ session, runs }: { session: Session; runs: Run[] }) {
  const running = session.state === 'RUNNING';
  const failed = session.state === 'FAILED';

  return (
    <>
      <div>
        <Group gap="sm">
          <Title order={2} size="h3" style={{ textWrap: 'balance' }}>
            {session.title}
          </Title>
          <SessionBadge state={session.state} />
        </Group>
        <Text ff="monospace" size="sm" c="dimmed" mt={6}>
          {session.repoFullName}#{session.number}
        </Text>
      </div>

      <Grid gap="xl">
        <Grid.Col span={{ base: 12, sm: 8 }}>
          <Timeline active={running ? 0 : 1} bulletSize={26} lineWidth={2} color="fern">
            <Timeline.Item bullet={<IconSprout size={13} />} title="Session started" color="fern">
              <Text size="sm" c="dimmed">
                Picked up {session.repoFullName}#{session.number}.
              </Text>
              <Text size="xs" c="dimmed" ff="monospace" mt={2}>
                {clockTime(session.startedAtMillis)}
              </Text>
            </Timeline.Item>

            {runs.flatMap((run) =>
              run.attempts.map((attempt) => (
                <Timeline.Item
                  key={`${run.ordinal}-${attempt.number}`}
                  bullet={<IconActivity size={13} />}
                  title={attemptTitle(run, attempt)}
                  color={attempt.state === 'abandoned' ? 'gray' : 'fern'}
                  lineVariant={attempt.state === 'abandoned' ? 'dashed' : 'solid'}
                >
                  <AttemptEntries attempt={attempt} />
                </Timeline.Item>
              ))
            )}

            {running ? (
              <Timeline.Item
                bullet={<IconActivity size={13} />}
                title="Working…"
                color="yellow"
                lineVariant="dashed"
              >
                <Text size="sm" c="dimmed">
                  The agent is processing this issue.
                </Text>
              </Timeline.Item>
            ) : (
              <Timeline.Item
                bullet={failed ? <IconX size={13} /> : <IconCheck size={14} />}
                title={failed ? 'Failed' : 'Completed'}
                color={failed ? 'red' : 'fern'}
              >
                <Text size="sm" c="dimmed">
                  {failed ? 'The run could not complete.' : 'Finished processing this issue.'}
                </Text>
                <Text size="xs" c="dimmed" ff="monospace" mt={2}>
                  {clockTime(session.finishedAtMillis)}
                </Text>
              </Timeline.Item>
            )}
          </Timeline>

          {runs.length === 0 && (
            <Text size="xs" c="dimmed" mt="xl" style={{ maxWidth: '60ch' }}>
              The agent's actions will appear here as it takes them.
            </Text>
          )}
        </Grid.Col>

        <Grid.Col span={{ base: 12, sm: 4 }}>
          <Card withBorder radius="md" padding="md">
            <Text
              tt="uppercase"
              fw={600}
              size="xs"
              c="dimmed"
              mb="sm"
              style={{ letterSpacing: '.07em' }}
            >
              Session
            </Text>
            <Stack gap="xs">
              <MetaRow label="State">
                <SessionBadge state={session.state} />
              </MetaRow>
              <MetaRow label="Issue">
                <Text span ff="monospace" size="sm">
                  #{session.number}
                </Text>
              </MetaRow>
              <MetaRow label="Repository">
                <Text span ff="monospace" size="sm">
                  {session.repoFullName.split('/').pop()}
                </Text>
              </MetaRow>
              {session.pullRequestUrl && (
                <MetaRow label="Pull request">
                  <Anchor href={session.pullRequestUrl} target="_blank" size="sm">
                    #{session.pullRequestUrl.split('/').pop()}
                  </Anchor>
                </MetaRow>
              )}
              <MetaRow label="Started">
                <Text span ff="monospace" size="sm">
                  {clockTime(session.startedAtMillis)}
                </Text>
              </MetaRow>
              <MetaRow label="Finished">
                <Text span ff="monospace" size="sm">
                  {session.finishedAtMillis > 0 ? clockTime(session.finishedAtMillis) : '—'}
                </Text>
              </MetaRow>
              <MetaRow label="Duration">
                <Text span ff="monospace" size="sm">
                  {session.finishedAtMillis > 0
                    ? duration(session.startedAtMillis, session.finishedAtMillis)
                    : relativeTime(session.startedAtMillis)}
                </Text>
              </MetaRow>
              <MetaRow label="Session id">
                <Text span ff="monospace" size="xs">
                  {session.id.slice(0, 8)}…
                </Text>
              </MetaRow>
            </Stack>
          </Card>
        </Grid.Col>
      </Grid>
    </>
  );
}

/** A run only names its try where there was more than one — otherwise the number is noise. */
function attemptTitle(run: Run, attempt: RunAttempt): string {
  const what = run.ordinal === 0 ? 'Implementing' : `Fixup ${run.ordinal}`;
  if (run.attempts.length === 1) {
    return what;
  }
  return attempt.state === 'abandoned'
    ? `${what} — try ${attempt.number}, abandoned`
    : `${what} — try ${attempt.number}`;
}

function AttemptEntries({ attempt }: { attempt: RunAttempt }) {
  if (attempt.entries.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {attempt.state === 'abandoned' ? 'Stopped before doing anything.' : 'Getting started…'}
      </Text>
    );
  }

  return (
    <Stack gap={6} mt={4}>
      {attempt.entries.map((entry, index) => (
        <div key={index}>
          {entry.kind === 'warning' ? (
            <Text size="sm" c="yellow.7" ff="monospace">
              {entry.text}
            </Text>
          ) : (
            <>
              {entry.text && <Text size="sm">{entry.text}</Text>}
              {entry.actions.map((action, actionIndex) => (
                <Text key={actionIndex} size="xs" c="dimmed" ff="monospace">
                  {action}
                </Text>
              ))}
            </>
          )}
        </div>
      ))}
      {attempt.summary && (
        <Text size="xs" c="dimmed" mt={4} fs="italic">
          {attempt.summary}
        </Text>
      )}
    </Stack>
  );
}
