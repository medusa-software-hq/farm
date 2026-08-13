import {
  Anchor,
  Card,
  Group,
  Loader,
  Pagination,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { IconArrowLeft, IconSearch } from '../icons.tsx';
import { SessionBadge } from '../SessionBadge.tsx';
import { clockTime, duration, relativeTime } from '../time.ts';
import { useSessions } from '../useSessions.ts';

const PAGE_SIZE = 12;

export function SessionsArchive() {
  const sessions = useSessions();
  const navigate = useNavigate();
  const [state, setState] = useState<string>('all');
  const [repo, setRepo] = useState<string>('all');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);

  const repos = useMemo(
    () => Array.from(new Set((sessions ?? []).map((s) => s.repoFullName))).sort(),
    [sessions]
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (sessions ?? []).filter(
      (s) =>
        (state === 'all' || s.state === state) &&
        (repo === 'all' || s.repoFullName === repo) &&
        (q === '' || s.title.toLowerCase().includes(q) || String(s.number).includes(q))
    );
  }, [sessions, state, repo, query]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const current = Math.min(page, pageCount);
  const rows = filtered.slice((current - 1) * PAGE_SIZE, current * PAGE_SIZE);

  // Any filter change resets to the first page.
  function onFilter<T>(setter: (v: T) => void) {
    return (v: T) => {
      setter(v);
      setPage(1);
    };
  }

  return (
    <Stack gap="md">
      <div>
        <Anchor component={Link} to="/sessions" c="dimmed" size="sm">
          <Group gap={4} component="span">
            <IconArrowLeft size={14} /> Sessions
          </Group>
        </Anchor>
        <Title order={2} size="h3" mt="xs">
          Session archive
        </Title>
        <Text c="dimmed" size="sm">
          Every session Farm has run for this org.
        </Text>
      </div>

      {sessions === null ? (
        <Loader size="sm" color="fern" />
      ) : (
        <>
          <Group gap="sm">
            <Select
              size="sm"
              w={150}
              value={state}
              onChange={(v) => onFilter(setState)(v ?? 'all')}
              data={[
                { value: 'all', label: 'All states' },
                { value: 'RUNNING', label: 'Running' },
                { value: 'COMPLETED', label: 'Completed' },
                { value: 'FAILED', label: 'Failed' },
              ]}
            />
            <Select
              size="sm"
              w={230}
              value={repo}
              onChange={(v) => onFilter(setRepo)(v ?? 'all')}
              data={[
                { value: 'all', label: 'All repositories' },
                ...repos.map((r) => ({ value: r, label: r.split('/').pop() ?? r })),
              ]}
            />
            <TextInput
              size="sm"
              flex={1}
              maw={260}
              placeholder="Search issues…"
              value={query}
              onChange={(e) => onFilter(setQuery)(e.currentTarget.value)}
              leftSection={<IconSearch size={15} />}
            />
            <Text c="dimmed" size="sm" ml="auto" style={{ fontVariantNumeric: 'tabular-nums' }}>
              {filtered.length} {filtered.length === 1 ? 'session' : 'sessions'}
            </Text>
          </Group>

          <Card withBorder padding={0} radius="md">
            <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Issue</Table.Th>
                  <Table.Th w={130}>State</Table.Th>
                  <Table.Th w={110}>Started</Table.Th>
                  <Table.Th w={90}>Duration</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.length === 0 ? (
                  <Table.Tr>
                    <Table.Td colSpan={4}>
                      <Text c="dimmed" size="sm" py="md" ta="center">
                        No sessions match.
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ) : (
                  rows.map((s) => (
                    <Table.Tr
                      key={s.id}
                      onClick={() => navigate(`/sessions/${s.id}`)}
                      style={{ cursor: 'pointer' }}
                    >
                      <Table.Td>
                        <Text ff="monospace" size="xs" c="dimmed">
                          {s.repoFullName}#{s.number}
                        </Text>
                        <Text size="sm" fw={520} lineClamp={1}>
                          {s.title}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <SessionBadge state={s.state} />
                      </Table.Td>
                      <Table.Td>
                        <Text size="xs" c="dimmed" title={clockTime(s.startedAtMillis)}>
                          {relativeTime(s.startedAtMillis)}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="xs" c="dimmed" ff="monospace">
                          {duration(s.startedAtMillis, s.finishedAtMillis)}
                        </Text>
                      </Table.Td>
                    </Table.Tr>
                  ))
                )}
              </Table.Tbody>
            </Table>
          </Card>

          {pageCount > 1 && (
            <Group justify="center">
              <Pagination
                total={pageCount}
                value={current}
                onChange={setPage}
                color="fern"
                size="sm"
              />
            </Group>
          )}
        </>
      )}
    </Stack>
  );
}
