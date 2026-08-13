import { AppShell, Avatar, Box, Button, Group, NavLink, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { toast } from 'sonner';
import { farm } from './api.ts';
import { IconIssue, IconOverview, IconRepo, IconSessions, IconSprout, IconSync } from './icons.tsx';
import { OrgSwitcher } from './OrgSwitcher.tsx';
import { useApiHeaders } from './useApiHeaders.ts';
import { useAuth } from './useAuth.tsx';

const NAV = [
  { to: '/', label: 'Overview', Icon: IconOverview },
  { to: '/repositories', label: 'Repositories', Icon: IconRepo },
  { to: '/issues', label: 'Issues', Icon: IconIssue },
  { to: '/sessions', label: 'Sessions', Icon: IconSessions },
];

// A nav entry owns its path and everything under it (so /sessions stays active on /sessions/:id).
function ownsPath(to: string, pathname: string): boolean {
  return to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`);
}

export function AppLayout() {
  const { pathname } = useLocation();
  const { state } = useAuth();
  const headers = useApiHeaders();
  const [syncing, setSyncing] = useState(false);

  const user = state.status === 'authenticated' ? state.user : null;
  const title = NAV.find((n) => ownsPath(n.to, pathname))?.label ?? 'Farm';

  async function sync() {
    setSyncing(true);
    try {
      await farm.syncRepositories({}, { headers });
      toast.success('Sync started');
    } catch {
      toast.error('Could not start the sync — the API may be unreachable.');
    } finally {
      setSyncing(false);
    }
  }

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 250, breakpoint: 'sm' }} padding="lg">
      <AppShell.Header>
        <Group h="100%" px="lg" gap="sm">
          <Text fw={600}>{title}</Text>
          <Box style={{ flex: 1 }} />
          <Button
            variant="default"
            size="xs"
            leftSection={<IconSync size={14} />}
            loading={syncing}
            onClick={() => void sync()}
          >
            Sync
          </Button>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm" style={{ display: 'flex', flexDirection: 'column' }}>
        <Group gap={8} px={6} pb="xs">
          <Avatar radius="md" size={26} color="fern" variant="filled">
            <IconSprout size={15} />
          </Avatar>
          <Text fw={650} size="15px">
            Farm
          </Text>
        </Group>

        <OrgSwitcher />

        <Box mt="md">
          {NAV.map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              component={Link}
              to={to}
              label={label}
              leftSection={<Icon size={17} />}
              active={ownsPath(to, pathname)}
              variant="light"
              style={{ borderRadius: 'var(--mantine-radius-md)' }}
            />
          ))}
        </Box>

        <Group gap="xs" mt="auto" px={4} pt="sm" wrap="nowrap">
          <Avatar size={26} radius="xl" src={user?.picture || undefined} color="gray">
            {user?.name?.[0]?.toUpperCase() ?? 'U'}
          </Avatar>
          <div style={{ minWidth: 0 }}>
            <Text size="xs" fw={550} truncate>
              {user?.name ?? 'User'}
            </Text>
            <Text size="10px" c="dimmed" truncate>
              {user?.email}
            </Text>
          </div>
        </Group>
      </AppShell.Navbar>

      <AppShell.Main>
        <Box maw={1040} mx="auto">
          <Outlet />
        </Box>
      </AppShell.Main>
    </AppShell>
  );
}
