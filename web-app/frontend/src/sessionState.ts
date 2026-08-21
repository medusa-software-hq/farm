// The session state arrives as a string, so these read it in one place: the screens ask what a
// state means rather than which word it is.

/**
 * Whether Farm is still on this session: either working it, or holding it open for a review.
 * Neither has an ending to show.
 */
export function isLive(state: string): boolean {
  return state === 'RUNNING' || state === 'AWAITING_REVIEW';
}

/** Whether the session is waiting on a person — the next move is theirs, not Farm's. */
export function isAwaitingReview(state: string): boolean {
  return state === 'AWAITING_REVIEW';
}
