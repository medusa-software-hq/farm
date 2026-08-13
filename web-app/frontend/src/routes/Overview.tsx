import { Card, Group, Loader, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { farm } from '../api.ts';
import { useOrg } from '../OrgContext.tsx';
import { useApiHeaders } from '../useApiHeaders.ts';

interface Snapshot {
  repos: number;
  issues: number;
  running: number;
  completed: number;
}

function Stat({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <Card withBorder radius="md" padding="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600} style={{ letterSpacing: '.06em' }}>
        {label}
      </Text>
      <Text fz={26} fw={660} mt={4} c={color} style={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Text>
    </Card>
  );
}

export function Overview() {
  const headers = useApiHeaders();
  const { selected } = useOrg();
  const [snap, setSnap] = useState<Snapshot | null>(null);

  useEffect(() => {
    if (!selected) {
      return;
    }
    let cancelled = false;
    const prefix = `${selected.orgLogin}/`;
    void (async () => {
      try {
        const [repos, issues] = await Promise.all([
          farm.listRepositories({}, { headers }),
          farm.listIssues({}, { headers }),
        ]);
        if (cancelled) {
          return;
        }
        const orgIssues = issues.issues.filter((i) => i.repoFullName.startsWith(prefix));
        setSnap({
          repos: repos.repositories.filter((r) => r.orgLogin === selected.orgLogin).length,
          issues: orgIssues.length,
          running: orgIssues.filter((i) => i.sessionState === 'RUNNING').length,
          completed: orgIssues.filter((i) => i.sessionState === 'COMPLETED').length,
        });
      } catch {
        if (!cancelled) {
          setSnap({ repos: 0, issues: 0, running: 0, completed: 0 });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [headers, selected]);

  return (
    <Stack gap="lg">
      <div>
        <Title order={2} size="h3">
          Overview
        </Title>
        <Text c="dimmed" size="sm">
          {selected?.orgLogin ?? 'No organization'} — what Farm sees and what it's working.
        </Text>
      </div>

      {snap === null ? (
        <Loader size="sm" color="fern" />
      ) : (
        <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="md">
          <Stat label="Repositories" value={snap.repos} />
          <Stat label="Open issues" value={snap.issues} />
          <Stat label="Running now" value={snap.running} color="yellow.7" />
          <Stat label="Completed" value={snap.completed} color="fern" />
        </SimpleGrid>
      )}

      <Group gap="xs">
        <Text size="sm" c="dimmed">
          Label an issue
        </Text>
        <Text size="sm" c="fern" fw={600} ff="monospace">
          farm:ready
        </Text>
        <Text size="sm" c="dimmed">
          and the next sync opens a session for it.
        </Text>
      </Group>
    </Stack>
  );
}
