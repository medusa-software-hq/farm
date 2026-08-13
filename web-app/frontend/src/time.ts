/** Small time helpers for the sessions views — no date library needed. */

/** "just now", "3m ago", "2h ago", "Aug 12" — relative for recent, absolute for older. */
export function relativeTime(millis: number, now: number = Date.now()): string {
  if (millis <= 0) {
    return '';
  }
  const secs = Math.max(0, Math.round((now - millis) / 1000));
  if (secs < 45) {
    return 'just now';
  }
  const mins = Math.round(secs / 60);
  if (mins < 60) {
    return `${mins}m ago`;
  }
  const hours = Math.round(mins / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  return new Date(millis).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** A wall-clock time like "10:31:29". */
export function clockTime(millis: number): string {
  if (millis <= 0) {
    return '';
  }
  return new Date(millis).toLocaleTimeString(undefined, { hour12: false });
}

/** A compact duration between two epoch-millis, e.g. "6s" or "1m 20s". `finish` 0 means running. */
export function duration(startMillis: number, finishMillis: number): string {
  if (startMillis <= 0 || finishMillis <= 0) {
    return '';
  }
  const secs = Math.max(0, Math.round((finishMillis - startMillis) / 1000));
  if (secs < 60) {
    return `${secs}s`;
  }
  return `${Math.floor(secs / 60)}m ${secs % 60}s`;
}
