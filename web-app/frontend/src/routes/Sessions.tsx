import {
  Badge,
  Button,
  Card,
  Group,
  Loader,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { IconActivity, IconSessions } from '../icons.tsx';
import { SessionBadge } from '../SessionBadge.tsx';
import { duration, relativeTime } from '../time.ts';
import { type Session, useSessions } from '../useSessions.ts';

function SectionHead({ label, count }: { label: string; count?: number }) {
  return (
    <Group gap="xs" mb="sm">
      <Text tt="uppercase" fw={660} size="xs" c="dimmed" style={{ letterSpacing: '.08em' }}>
        {label}
      </Text>
      {count !== undefined && (
        <Badge variant="light" color="gray" size="sm" radius="xl">
          {count}
        </Badge>
      )}
    </Group>
  );
}

function ActiveCard({ session, now }: { session: Session; now: number }) {
  const navigate = useNavigate();
  const elapsed = duration(session.startedAtMillis, now);
  return (
    <Card
      withBorder
      radius="md"
      padding="md"
      onClick={() => navigate(`/sessions/${session.id}`)}
      style={{ cursor: 'pointer', borderLeft: '3px solid var(--mantine-color-yellow-6)' }}
    >
      <Group justify="space-between" wrap="nowrap">
        <Text ff="monospace" size="xs" c="dimmed" truncate>
          {session.repoFullName}#{session.number}
        </Text>
        <SessionBadge state={session.state} />
      </Group>
      <Text fw={550} mt={6} lineClamp={1}>
        {session.title}
      </Text>
      <Group gap="xs" mt="sm" c="dimmed">
        <Text c="yellow.7" style={{ display: 'inline-flex' }}>
          <IconActivity size={14} />
        </Text>
        <Text size="sm">Working…</Text>
        <Text size="sm" ff="monospace" ml="auto" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {elapsed} elapsed
        </Text>
      </Group>
    </Card>
  );
}

export function Sessions() {
  const sessions = useSessions(3000);
  const navigate = useNavigate();
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const active = (sessions ?? []).filter((s) => s.state === 'RUNNING');
  const recent = (sessions ?? []).filter((s) => s.state !== 'RUNNING').slice(0, 6);

  return (
    <Stack gap="xl">
      <div>
        <Title order={2} size="h3">
          Sessions
        </Title>
        <Text c="dimmed" size="sm">
          Every run of the Farm agent against an issue.
        </Text>
      </div>

      {sessions === null ? (
        <Loader size="sm" color="fern" />
      ) : (
        <>
          <div>
            <SectionHead label="Active" count={active.length} />
            {active.length === 0 ? (
              <Text c="dimmed" size="sm">
                Nothing running right now.
              </Text>
            ) : (
              <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
                {active.map((s) => (
                  <ActiveCard key={s.id} session={s} now={now} />
                ))}
              </SimpleGrid>
            )}
          </div>

          <div>
            <SectionHead label="Recent" />
            {recent.length === 0 ? (
              <Text c="dimmed" size="sm">
                No finished sessions yet.
              </Text>
            ) : (
              <Card withBorder padding={0} radius="md">
                <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
                  <Table.Tbody>
                    {recent.map((s) => (
                      <Table.Tr
                        key={s.id}
                        onClick={() => navigate(`/sessions/${s.id}`)}
                        style={{ cursor: 'pointer' }}
                      >
                        <Table.Td>
                          <Text ff="monospace" size="xs" c="dimmed">
                            {s.repoFullName}#{s.number}
                          </Text>
                          <Text size="sm" fw={520}>
                            {s.title}
                          </Text>
                        </Table.Td>
                        <Table.Td w={130}>
                          <SessionBadge state={s.state} />
                        </Table.Td>
                        <Table.Td w={110}>
                          <Text size="xs" c="dimmed">
                            {relativeTime(s.finishedAtMillis, now)}
                          </Text>
                        </Table.Td>
                        <Table.Td w={70}>
                          <Text size="xs" c="dimmed" ff="monospace">
                            {duration(s.startedAtMillis, s.finishedAtMillis)}
                          </Text>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Card>
            )}
            <Button
              component={Link}
              to="/sessions/archive"
              variant="default"
              size="sm"
              mt="md"
              leftSection={<IconSessions size={15} />}
            >
              Show older sessions
            </Button>
          </div>
        </>
      )}
    </Stack>
  );
}
