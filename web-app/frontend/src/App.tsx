import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { Box, Button, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import heroImg from './assets/hero.png';
import reactLogo from './assets/react.svg';
import viteLogo from './assets/vite.svg';
import { FarmService } from './gen/medusa/farm/v1/farm_service_pb.ts';
import { SignInWall } from './SignInWall.tsx';
import { useAuth } from './useAuth.tsx';
import classes from './App.module.css';

const API_URL = import.meta.env.VITE_API_URL as string;

if (!API_URL) {
  throw new Error('VITE_API_URL is not set');
}

const transport = createGrpcWebTransport({
  baseUrl: API_URL,
});

const client = createClient(FarmService, transport);

const socialLinks = [
  { label: 'GitHub', href: 'https://github.com/vitejs/vite', icon: 'github-icon' },
  { label: 'Discord', href: 'https://chat.vite.dev/', icon: 'discord-icon' },
  { label: 'X.com', href: 'https://x.com/vite_js', icon: 'x-icon' },
  { label: 'Bluesky', href: 'https://bsky.app/profile/vite.dev', icon: 'bluesky-icon' },
];

function AppContent({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [linkedOrgs, setLinkedOrgs] = useState<{ orgLogin: string; installationId: bigint }[]>([]);
  const [repositories, setRepositories] = useState<{ orgLogin: string; fullName: string }[]>([]);
  const [issues, setIssues] = useState<{ repoFullName: string; number: number; title: string }[]>(
    []
  );
  const [syncing, setSyncing] = useState(false);

  const headers = useMemo(() => ({ Authorization: `Bearer ${token}` }), [token]);

  const handleError = useCallback(
    (err: unknown) => {
      const message = err instanceof Error ? err.message : String(err);
      if (message.includes('401') || message.includes('unauthenticated')) {
        handleUnauthorized();
      } else {
        setError(message);
      }
    },
    [handleUnauthorized]
  );

  // The org-link state: which GitHub orgs are linked to the Farm app. Reads as linked before any
  // repo has synced, so a fresh link shows up here even while Repositories is still empty.
  const loadLinkedOrgs = useCallback(async () => {
    try {
      const response = await client.listLinkedOrgs({}, { headers });
      setLinkedOrgs(
        response.orgs.map((o) => ({
          orgLogin: o.orgLogin,
          installationId: o.installationId,
        }))
      );
    } catch (err: unknown) {
      handleError(err);
    }
  }, [headers, handleError]);

  // Lands dark until the GitHub App is configured: an unavailable (UNIMPLEMENTED) or empty response
  // degrades to a quiet empty state rather than an error banner or a broken view.
  const loadRepositories = useCallback(async () => {
    try {
      const response = await client.listRepositories({}, { headers });
      setRepositories(
        response.repositories.map((r) => ({
          orgLogin: r.orgLogin,
          fullName: r.fullName,
        }))
      );
    } catch {
      setRepositories([]);
    }
  }, [headers]);

  // The open issues Farm has synced across every linked org's repos. Same quiet-empty degrade as
  // repositories — it fills in once a sync lands.
  const loadIssues = useCallback(async () => {
    try {
      const response = await client.listIssues({}, { headers });
      setIssues(
        response.issues.map((i) => ({
          repoFullName: i.repoFullName,
          number: i.number,
          title: i.title,
        }))
      );
    } catch {
      setIssues([]);
    }
  }, [headers]);

  useEffect(() => {
    void loadLinkedOrgs();
  }, [loadLinkedOrgs]);

  useEffect(() => {
    void loadRepositories();
  }, [loadRepositories]);

  useEffect(() => {
    void loadIssues();
  }, [loadIssues]);

  // Triggers the all-orgs sweep, then — after a short beat for the worker to run — refreshes the
  // lists. The sweep is async, so this single refresh is best-effort: a slow sync surfaces on the
  // next load rather than blocking the button.
  const handleSync = useCallback(async () => {
    setSyncing(true);
    setError(null);
    try {
      await client.syncRepositories({}, { headers });
    } catch (err: unknown) {
      handleError(err);
      setSyncing(false);
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
    await Promise.all([loadRepositories(), loadLinkedOrgs(), loadIssues()]);
    setSyncing(false);
  }, [headers, handleError, loadRepositories, loadLinkedOrgs, loadIssues]);

  return (
    <>
      <Box className={classes.center}>
        <div className={classes.hero}>
          <img src={heroImg} className={classes.base} width="170" height="179" alt="" />
          <img src={reactLogo} className={classes.framework} alt="React logo" />
          <img src={viteLogo} className={classes.vite} alt="Vite logo" />
        </div>
        {error !== null && (
          <Text c="red" size="sm">
            Failed to reach the API: {error}
          </Text>
        )}
      </Box>

      <Stack align="center" gap="xs" mt="xl" mb="xl">
        <Title order={2}>Organizations</Title>
        <Text c="dimmed" size="sm">
          Linked to the Farm GitHub App.
        </Text>
        {linkedOrgs.length === 0 ? (
          <Text c="dimmed" size="sm">
            No organizations linked yet…
          </Text>
        ) : (
          <Stack gap={2} align="center">
            {linkedOrgs.map((o) => (
              <Text key={o.orgLogin} size="sm" ff="monospace">
                {o.orgLogin}{' '}
                <Text span c="dimmed">
                  (installation {o.installationId.toString()})
                </Text>
              </Text>
            ))}
          </Stack>
        )}
      </Stack>

      <Stack align="center" gap="xs" mt="xl" mb="xl">
        <Title order={2}>Repositories</Title>
        <Text c="dimmed" size="sm">
          Reachable through the Farm GitHub App.
        </Text>
        <Button
          variant="light"
          size="sm"
          loading={syncing}
          disabled={linkedOrgs.length === 0}
          onClick={() => void handleSync()}
        >
          Sync
        </Button>
        {repositories.length === 0 ? (
          <Text c="dimmed" size="sm">
            No repositories yet…
          </Text>
        ) : (
          <Stack gap="xs" align="center">
            {repositories.map((r) => (
              <Stack key={`${r.orgLogin}:${r.fullName}`} gap={2} align="center">
                <Text size="sm" ff="monospace">
                  {r.fullName}{' '}
                  <Text span c="dimmed">
                    ({r.orgLogin})
                  </Text>
                </Text>
              </Stack>
            ))}
          </Stack>
        )}
      </Stack>

      <Stack align="center" gap="xs" mt="xl" mb="xl">
        <Title order={2}>Issues</Title>
        <Text c="dimmed" size="sm">
          Open issues synced from the linked repos.
        </Text>
        {issues.length === 0 ? (
          <Text c="dimmed" size="sm">
            No issues yet…
          </Text>
        ) : (
          <Stack gap={2} align="center">
            {issues.map((i) => (
              <Text key={`${i.repoFullName}#${i.number}`} size="sm" ff="monospace">
                <Text span c="dimmed">
                  {i.repoFullName}#{i.number}
                </Text>{' '}
                {i.title}
              </Text>
            ))}
          </Stack>
        )}
      </Stack>

      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing={0} className={classes.nextSteps}>
        <Box className={classes.section}>
          <svg className={classes.sectionIcon} role="presentation" aria-hidden="true">
            <use href="/icons.svg#documentation-icon" />
          </svg>
          <Title order={2} mb={4}>
            Documentation
          </Title>
          <Text c="dimmed">Your questions, answered</Text>
          <Group gap="xs" mt="md">
            <Button
              component="a"
              href="https://vite.dev/"
              target="_blank"
              rel="noreferrer"
              variant="default"
              leftSection={<img className={classes.linkIcon} src={viteLogo} alt="" />}
            >
              Explore Vite
            </Button>
            <Button
              component="a"
              href="https://react.dev/"
              target="_blank"
              rel="noreferrer"
              variant="default"
              leftSection={<img className={classes.linkIcon} src={reactLogo} alt="" />}
            >
              Learn more
            </Button>
          </Group>
        </Box>

        <Box className={classes.section}>
          <svg className={classes.sectionIcon} role="presentation" aria-hidden="true">
            <use href="/icons.svg#social-icon" />
          </svg>
          <Title order={2} mb={4}>
            Connect with us
          </Title>
          <Text c="dimmed">Join the Vite community</Text>
          <Group gap="xs" mt="md">
            {socialLinks.map(({ label, href, icon }) => (
              <Button
                key={label}
                component="a"
                href={href}
                target="_blank"
                rel="noreferrer"
                variant="default"
                leftSection={
                  <svg className={classes.linkIcon} role="presentation" aria-hidden="true">
                    <use href={`/icons.svg#${icon}`} />
                  </svg>
                }
              >
                {label}
              </Button>
            ))}
          </Group>
        </Box>
      </SimpleGrid>
    </>
  );
}

function App() {
  const { state } = useAuth();

  if (state.status === 'loading') {
    return null;
  }
  if (state.status === 'unauthenticated') {
    return <SignInWall />;
  }
  return <AppContent token={state.token} />;
}

export default App;
