// The posting cooldown (D-12) is judged by the server's clock: the client keeps the difference between
// its clock and the server's from the page load (`serverTime`).

export function clockOffset(serverTime: string, now: number = Date.now()): number {
  const server = Date.parse(serverTime);
  return Number.isNaN(server) ? 0 : server - now;
}

// Milliseconds until posting is possible again; 0 when it is possible now.
export function cooldownLeft(endsAt: string | null, offsetMs: number, now: number = Date.now()): number {
  if (!endsAt) {
    return 0;
  }
  const end = Date.parse(endsAt);
  return Number.isNaN(end) ? 0 : Math.max(0, end - (now + offsetMs));
}

// Whole minutes shown to the user, rounded up so "0분" never shows while waiting.
export function minutesLeft(ms: number): number {
  return Math.ceil(ms / 60_000);
}

// The end of a cooldown the server reported in seconds (COOLDOWN `retryAfterSec`), on the server's clock.
export function endsAfter(seconds: number, offsetMs: number, now: number = Date.now()): string {
  return new Date(now + offsetMs + seconds * 1000).toISOString();
}
