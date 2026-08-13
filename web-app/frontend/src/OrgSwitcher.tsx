import { Avatar, Group, Menu, Text, UnstyledButton } from '@mantine/core';
import { IconChevron, IconCheck } from './icons.tsx';
import { useOrg } from './OrgContext.tsx';

function initials(login: string): string {
  const parts = login.split(/[-_./]/).filter(Boolean);
  const from = parts.length >= 2 ? parts.slice(0, 2).map((p) => p[0]) : [login[0], login[1]];
  return from.join('').toUpperCase();
}

export function OrgSwitcher() {
  const { orgs, selected, select } = useOrg();

  return (
    <Menu position="bottom-start" width="target" shadow="md" radius="md" withinPortal>
      <Menu.Target>
        <UnstyledButton
          style={{
            width: '100%',
            padding: '7px 8px',
            border: '1px solid var(--mantine-color-default-border)',
            borderRadius: 'var(--mantine-radius-md)',
          }}
        >
          <Group gap="xs" wrap="nowrap">
            <Avatar radius="sm" size={24} color="fern" variant="filled">
              {selected ? initials(selected.orgLogin) : '—'}
            </Avatar>
            <div style={{ flex: 1, minWidth: 0 }}>
              <Text size="sm" fw={600} truncate>
                {selected?.orgLogin ?? 'No organization'}
              </Text>
              <Text size="10px" c="dimmed">
                Organization
              </Text>
            </div>
            <Text c="dimmed" component="span" style={{ display: 'inline-flex' }}>
              <IconChevron size={15} />
            </Text>
          </Group>
        </UnstyledButton>
      </Menu.Target>

      <Menu.Dropdown>
        <Menu.Label>Organizations</Menu.Label>
        {(orgs ?? []).map((o) => (
          <Menu.Item
            key={o.orgLogin}
            onClick={() => select(o.orgLogin)}
            leftSection={
              <Avatar radius="sm" size={20} color="fern" variant="filled">
                {initials(o.orgLogin)}
              </Avatar>
            }
            rightSection={o.orgLogin === selected?.orgLogin ? <IconCheck size={14} /> : undefined}
          >
            {o.orgLogin}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}
