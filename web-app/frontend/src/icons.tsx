import type { ReactNode } from 'react';

type IconProps = { size?: number };

function Svg({ children, size = 18 }: { children: ReactNode; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  );
}

export function IconOverview({ size }: IconProps) {
  return (
    <Svg size={size}>
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </Svg>
  );
}

export function IconRepo({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M4 4.5A2.5 2.5 0 0 1 6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5Z" />
      <path d="M4 17.5A2.5 2.5 0 0 1 6.5 15H20" />
    </Svg>
  );
}

export function IconIssue({ size }: IconProps) {
  return (
    <Svg size={size}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v4M12 16h.01" />
    </Svg>
  );
}

export function IconSessions({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M3 12h4l2 6 4-14 2 8h6" />
    </Svg>
  );
}

export function IconSync({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
      <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
      <path d="M21 3v5h-5M3 21v-5h5" />
    </Svg>
  );
}

export function IconChevron({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="m7 9 5 5 5-5" />
    </Svg>
  );
}

export function IconCheck({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M20 6 9 17l-5-5" />
    </Svg>
  );
}

export function IconSprout({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M7 20c0-6 4-9 10-9-1 6-4 9-10 9Z" />
      <path d="M7 20c0-4-2-7-5-8 3-1 5 0 6 2" />
      <path d="M7 20v-9" />
    </Svg>
  );
}

export function IconArrowLeft({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="m15 18-6-6 6-6" />
    </Svg>
  );
}

export function IconSearch({ size }: IconProps) {
  return (
    <Svg size={size}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3-3" />
    </Svg>
  );
}

export function IconX({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M18 6 6 18M6 6l12 12" />
    </Svg>
  );
}

export function IconActivity({ size }: IconProps) {
  return (
    <Svg size={size}>
      <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
    </Svg>
  );
}
