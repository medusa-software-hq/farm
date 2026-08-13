import { Card, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { farm } from '../api.ts';
import { useOrg } from '../OrgContext.tsx';
import { useApiHeaders } from '../useApiHeaders.ts';

interface Repo {
  orgLogin: string;
  fullName: string;
}

export function Repositories() {
  const headers = useApiHeaders();
  const { selected } = useOrg();
  const [repos, setRepos] = useState<Repo[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const res = await farm.listRepositories({}, { headers });
        if (!cancelled) {
          setRepos(res.repositories.map((r) => ({ orgLogin: r.orgLogin, fullName: r.fullName })));
        }
      } catch {
        if (!cancelled) {
          setRepos([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [headers]);

  const rows = (repos ?? []).filter((r) => r.orgLogin === selected?.orgLogin);

  return (
    <Stack gap="lg">
      <div>
        <Title order={2} size="h3">
          Repositories
        </Title>
        <Text c="dimmed" size="sm">
          Synced from {selected?.orgLogin ?? 'the org'} through the Farm GitHub App.
        </Text>
      </div>

      {repos === null ? (
        <Loader size="sm" color="fern" />
      ) : rows.length === 0 ? (
        <Text c="dimmed" size="sm">
          No repositories yet…
        </Text>
      ) : (
        <Card withBorder padding={0} radius="md">
          <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.fullName}>
                  <Table.Td>
                    <Text ff="monospace" size="sm" fw={550}>
                      {r.fullName}
                    </Text>
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
