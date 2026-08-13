import { createTheme, type MantineColorsTuple } from '@mantine/core';

// Farm's botanical fern accent — tied to the app's identity (growth, the 🌱), used for
// primary/active states. Semantic status colors (running/completed/failed) stay separate.
const fern: MantineColorsTuple = [
  '#eef7f1',
  '#dcece2',
  '#b7d8c5',
  '#8ec3a4',
  '#6cb188',
  '#57a577',
  '#489e6d',
  '#38865a',
  '#2d774f',
  '#186340',
];

export const theme = createTheme({
  primaryColor: 'fern',
  primaryShade: { light: 7, dark: 5 },
  colors: { fern },
  fontFamily: 'ui-sans-serif, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
  fontFamilyMonospace: 'ui-monospace, "SF Mono", Menlo, monospace',
  defaultRadius: 'md',
  headings: { fontWeight: '650' },
});
