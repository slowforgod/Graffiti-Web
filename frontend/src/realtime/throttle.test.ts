import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { throttle } from './throttle';

describe('throttle', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('sends the first value at once and the last of a burst after the interval', () => {
    const sent: number[] = [];
    const t = throttle<number>(100, (v) => sent.push(v), () => Date.now());
    t.push(1);
    t.push(2);
    t.push(3);
    expect(sent).toEqual([1]);
    vi.advanceTimersByTime(100);
    expect(sent).toEqual([1, 3]);
    vi.advanceTimersByTime(500);
    t.push(4);
    expect(sent).toEqual([1, 3, 4]);
  });

  it('drops the pending value on cancel', () => {
    const sent: number[] = [];
    const t = throttle<number>(100, (v) => sent.push(v), () => Date.now());
    t.push(1);
    t.push(2);
    t.cancel();
    vi.advanceTimersByTime(200);
    expect(sent).toEqual([1]);
  });
});
