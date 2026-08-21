import { render, screen } from '@test-utils';
import { SessionBadge } from './SessionBadge.tsx';

test('a session waiting on a review says so', () => {
  render(<SessionBadge state="AWAITING_REVIEW" />);
  expect(screen.getByText('Awaiting review')).toBeInTheDocument();
});

test('a state nothing is known about reads as not started', () => {
  render(<SessionBadge state="" />);
  expect(screen.getByText('Not started')).toBeInTheDocument();
});
