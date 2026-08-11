import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { Box, Button, Group, NumberInput, SimpleGrid, Stack, Text, Title } from '@mantine/core';
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
  const [count, setCount] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fibonacci, setFibonacci] = useState<{ index: number; value: string }[]>([]);
  const [through, setThrough] = useState<number>(20);
  const [computing, setComputing] = useState(false);
  const [computeError, setComputeError] = useState<string | null>(null);
  const [repositories, setRepositories] = useState<
    { orgLogin: string; fullName: string; recentIssues: { number: number; title: string }[] }[]
  >([]);

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

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await client.getCount({}, { headers });
        if (!cancelled) {
          setCount(response.count);
        }
      } catch (err: unknown) {
        if (!cancelled) {
          handleError(err);
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [headers, handleError]);

  // The worker computes Fibonacci numbers into the database out of band; poll so the list fills in.
  useEffect(() => {
    let cancelled = false;

    async function loadFibonacci() {
      try {
        const response = await client.listFibonacci({}, { headers });
        if (!cancelled) {
          setFibonacci(response.numbers.map((n) => ({ index: n.index, value: n.value })));
        }
      } catch (err: unknown) {
        if (!cancelled) {
          handleError(err);
        }
      }
    }

    void loadFibonacci();
    const interval = setInterval(() => void loadFibonacci(), 2000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [headers, handleError]);

  // Lands dark until the GitHub App is configured: an unavailable (UNIMPLEMENTED) or empty response
  // degrades to a quiet empty state rather than an error banner or a broken view.
  useEffect(() => {
    let cancelled = false;

    async function loadRepositories() {
      try {
        const response = await client.listRepositories({}, { headers });
        if (!cancelled) {
          setRepositories(
            response.repositories.map((r) => ({
              orgLogin: r.orgLogin,
              fullName: r.fullName,
              recentIssues: r.recentIssues.map((i) => ({ number: i.number, title: i.title })),
            }))
          );
        }
      } catch {
        if (!cancelled) {
          setRepositories([]);
        }
      }
    }

    void loadRepositories();
    return () => {
      cancelled = true;
    };
  }, [headers]);

  async function increment() {
    try {
      const response = await client.increment({}, { headers });
      setCount(response.count);
      setError(null);
    } catch (err: unknown) {
      handleError(err);
    }
  }

  async function decrement() {
    try {
      const response = await client.decrement({}, { headers });
      setCount(response.count);
      setError(null);
    } catch (err: unknown) {
      handleError(err);
    }
  }

  // Kicks off the Temporal workflow; the poll above then fills the list in as the worker persists.
  async function startFibonacci() {
    setComputing(true);
    setComputeError(null);
    try {
      await client.startFibonacci({ through }, { headers });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      setComputeError(message);
    } finally {
      setComputing(false);
    }
  }

  return (
    <>
      <Box className={classes.center}>
        <div className={classes.hero}>
          <img src={heroImg} className={classes.base} width="170" height="179" alt="" />
          <img src={reactLogo} className={classes.framework} alt="React logo" />
          <img src={viteLogo} className={classes.vite} alt="Vite logo" />
        </div>
        <Stack align="center" gap="md">
          <Title order={1} className={classes.count}>
            {count ?? '…'}
          </Title>
          <Group justify="center" gap="xs">
            <Button
              variant="light"
              size="md"
              aria-label="Decrement"
              onClick={() => void decrement()}
            >
              −
            </Button>
            <Button
              variant="light"
              size="md"
              aria-label="Increment"
              onClick={() => void increment()}
            >
              +
            </Button>
          </Group>
          {error !== null && (
            <Text c="red" size="sm">
              Failed to reach the API: {error}
            </Text>
          )}
        </Stack>
      </Box>

      <Stack align="center" gap="xs" mt="xl" mb="xl">
        <Title order={2}>Fibonacci</Title>
        <Text c="dimmed" size="sm">
          Computed by the worker, stored in the database.
        </Text>
        <Group justify="center" gap="xs" align="flex-end">
          <NumberInput
            aria-label="Compute through index"
            value={through}
            onChange={(value) => setThrough(typeof value === 'number' ? value : 0)}
            min={0}
            max={100}
            allowDecimal={false}
            w={120}
          />
          <Button
            variant="light"
            size="sm"
            loading={computing}
            onClick={() => void startFibonacci()}
          >
            Compute
          </Button>
        </Group>
        {computeError !== null && (
          <Text c="red" size="sm">
            Failed to start the computation: {computeError}
          </Text>
        )}
        {fibonacci.length === 0 ? (
          <Text c="dimmed" size="sm">
            No numbers yet…
          </Text>
        ) : (
          <Stack gap={2} align="center">
            {fibonacci.map((n) => (
              <Text key={n.index} size="sm" ff="monospace">
                F({n.index}) = {n.value}
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
                {r.recentIssues.map((i) => (
                  <Text key={i.number} size="xs" c="dimmed">
                    #{i.number} {i.title}
                  </Text>
                ))}
              </Stack>
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
