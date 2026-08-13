import { Card, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { farm } from '../api.ts';
import { useOrg } from '../OrgContext.tsx';
import { SessionBadge } from '../SessionBadge.tsx';
import { useApiHeaders } from '../useApiHeaders.ts';

interface Issue {
  repoFullName: string;
  number: number;
  title: string;
  sessionState: string;
}

export function Issues() {
  const headers = useApiHeaders();
  const { selected } = useOrg();
  const [issues, setIssues] = useState<Issue[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const res = await farm.listIssues({}, { headers });
        if (!cancelled) {
          setIssues(
            res.issues.map((i) => ({
              repoFullName: i.repoFullName,
              number: i.number,
              title: i.title,
              sessionState: i.sessionState,
            }))
          );
        }
      } catch {
        if (!cancelled) {
          setIssues([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [headers]);

  const prefix = selected ? `${selected.orgLogin}/` : null;
  const rows = (issues ?? []).filter((i) => prefix && i.repoFullName.startsWith(prefix));

  return (
    <Stack gap="lg">
      <div>
        <Title order={2} size="h3">
          Issues
        </Title>
        <Text c="dimmed" size="sm">
          Open issues Farm recognizes. Ones labeled{' '}
          <Text span c="fern" fw={600} ff="monospace">
            farm:ready
          </Text>{' '}
          get worked automatically.
        </Text>
      </div>

      {issues === null ? (
        <Loader size="sm" color="fern" />
      ) : rows.length === 0 ? (
        <Text c="dimmed" size="sm">
          No issues yet…
        </Text>
      ) : (
        <Card withBorder padding={0} radius="md">
          <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
            <Table.Tbody>
              {rows.map((i) => (
                <Table.Tr key={`${i.repoFullName}#${i.number}`}>
                  <Table.Td>
                    <Text ff="monospace" size="xs" c="dimmed">
                      {i.repoFullName}#{i.number}
                    </Text>
                    <Text size="sm" fw={520}>
                      {i.title}
                    </Text>
                  </Table.Td>
                  <Table.Td w={140}>
                    <Group justify="flex-end">
                      <SessionBadge state={i.sessionState} />
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}
    </Stack>
  );
}
