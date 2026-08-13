import { Card, Stack, Text, Title } from '@mantine/core';
import { IconSessions } from '../icons.tsx';

export function Sessions() {
  return (
    <Stack gap="lg">
      <div>
        <Title order={2} size="h3">
          Sessions
        </Title>
        <Text c="dimmed" size="sm">
          Every run of the Farm agent against an issue.
        </Text>
      </div>

      <Card withBorder radius="md" padding="xl">
        <Stack align="center" gap="sm" py="xl">
          <Text c="fern">
            <IconSessions size={28} />
          </Text>
          <Text fw={600}>Sessions land here next</Text>
          <Text c="dimmed" size="sm" ta="center" maw={440}>
            What's running now, what just finished, and the searchable archive — each session with
            its own timeline of agent actions — arrive in the next step.
          </Text>
        </Stack>
      </Card>
    </Stack>
  );
}
