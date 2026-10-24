import { describe, expect, it } from 'vitest';
import { clockText, nextTick, occupancyText, remainingText, startDateText, untilEnd } from './pageTime';

describe('pageTime', () => {
  it('measures the time left on the server clock', () => {
    const now = Date.parse('2026-10-21T00:00:00Z');
    expect(untilEnd('2026-10-21T00:10:00Z', 0, now)).toBe(600_000);
    // The client clock is a minute behind the server's.
    expect(untilEnd('2026-10-21T00:10:00Z', 60_000, now)).toBe(540_000);
    expect(untilEnd('2026-10-20T23:00:00Z', 0, now)).toBe(0);
    expect(untilEnd('not a date', 0, now)).toBe(0);
  });

  it('words the time left in days, hours and minutes, rounded up', () => {
    const minute = 60_000;
    expect(remainingText(7 * 24 * 60 * minute)).toBe('7일');
    expect(remainingText((6 * 24 * 60 + 23 * 60 + 1) * minute)).toBe('7일');
    expect(remainingText((24 * 60 + 61) * minute)).toBe('1일 2시간');
    expect(remainingText(6 * 60 * minute)).toBe('6시간');
    expect(remainingText((5 * 60 + 12) * minute - 1)).toBe('5시간 12분');
    expect(remainingText(59 * minute + 1)).toBe('1시간');
    expect(remainingText(30_000)).toBe('1분');
    expect(remainingText(0)).toBe('1분');
  });

  it('shows the countdown as minutes and seconds', () => {
    expect(clockText(300_000)).toBe('5:00');
    expect(clockText(299_001)).toBe('5:00');
    expect(clockText(299_000)).toBe('4:59');
    expect(clockText(9_000)).toBe('0:09');
  });

  it('shows occupancy with one decimal', () => {
    expect(occupancyText(712)).toBe('71.2%');
    expect(occupancyText(0)).toBe('0.0%');
  });

  it('ticks every second near the warning and every half minute before', () => {
    expect(nextTick(10 * 60_000, 5 * 60_000)).toBe(30_000);
    expect(nextTick(6 * 60_000, 5 * 60_000)).toBe(1000);
    expect(nextTick(1000, 5 * 60_000)).toBe(1000);
  });
});

describe('startDateText', () => {
  it('shows the start date as YY/MM/DD in local time', () => {
    const local = new Date(2026, 9, 4, 13, 5).toISOString();
    expect(startDateText(local)).toBe('26/10/04');
    expect(startDateText(new Date(2030, 0, 31, 0, 0).toISOString())).toBe('30/01/31');
  });

  it('shows nothing for a missing date', () => {
    expect(startDateText('')).toBe('');
  });
});
