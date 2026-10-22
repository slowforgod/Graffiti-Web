import { describe, expect, it } from 'vitest';
import { clockOffset, cooldownLeft, endsAfter, minutesLeft } from './cooldown';

describe('cooldown', () => {
  const now = Date.parse('2026-10-22T03:00:00Z');

  it('measures the remaining time on the server clock', () => {
    // The client clock is 2 minutes behind the server.
    const offset = clockOffset('2026-10-22T03:02:00Z', now);
    expect(offset).toBe(120_000);
    expect(cooldownLeft('2026-10-22T03:12:00Z', offset, now)).toBe(600_000);
    expect(cooldownLeft('2026-10-22T03:01:00Z', offset, now)).toBe(0);
    expect(cooldownLeft(null, offset, now)).toBe(0);
  });

  it('rounds minutes up', () => {
    expect(minutesLeft(1)).toBe(1);
    expect(minutesLeft(60_000)).toBe(1);
    expect(minutesLeft(60_001)).toBe(2);
    expect(minutesLeft(0)).toBe(0);
  });

  it('turns retryAfterSec into an end time on the server clock', () => {
    expect(endsAfter(90, 120_000, now)).toBe('2026-10-22T03:03:30.000Z');
  });
});
