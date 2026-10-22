import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DraftStroke } from './draft';
import { DraftSender } from './draftSender';

function setup() {
  const strokes: DraftStroke[] = [];
  const sent: Record<string, unknown>[] = [];
  const sender = new DraftSender({
    pageId: '7',
    draftId: 'd1',
    intervalMs: 100,
    send: (b) => sent.push(b as Record<string, unknown>),
    stroke: (i) => strokes[i] ?? null,
  });
  return { strokes, sent, sender };
}

describe('DraftSender', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('sends at most once per interval and joins batches with the last point sent (AC-9)', () => {
    const { strokes, sent, sender } = setup();
    strokes.push({ c: 1, w: 4, p: [0, 0] });
    sender.points(0);
    strokes[0]!.p.push(1, 1, 2, 2);
    sender.points(0);
    sender.points(0);
    expect(sent).toEqual([{ pageId: '7', draftId: 'd1', op: 'points', strokeIndex: 0, c: 1, w: 4, points: [0, 0] }]);

    vi.advanceTimersByTime(99);
    expect(sent).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(sent[1]).toMatchObject({ op: 'points', points: [0, 0, 1, 1, 2, 2] });

    vi.advanceTimersByTime(100);
    expect(sent).toHaveLength(2);
    strokes[0]!.p.push(3, 3);
    sender.points(0);
    expect(sent[2]).toMatchObject({ points: [2, 2, 3, 3] });
  });

  it('drops waiting points of an erased stroke and keeps the latest text box only', () => {
    const { strokes, sent, sender } = setup();
    strokes.push({ c: 0, w: 2, p: [5, 5] });
    sender.text({ x: 0, y: 0, w: 1, h: 1 });
    sender.points(0);
    sender.erase(0);
    sender.text({ x: 0, y: 0, w: 5, h: 5 });
    sender.text({ x: 0, y: 0, w: 9, h: 9 });
    vi.advanceTimersByTime(1000);

    expect(sent.map((m) => m.op)).toEqual(['text', 'erase', 'text']);
    expect(sent[2]).toMatchObject({ bbox: { w: 9, h: 9 } });
  });

  it('cancels at once and sends nothing more', () => {
    const { strokes, sent, sender } = setup();
    strokes.push({ c: 0, w: 2, p: [5, 5] });
    sender.points(0);
    strokes[0]!.p.push(6, 6);
    sender.points(0);
    sender.cancel();
    sender.points(0);
    vi.advanceTimersByTime(1000);

    expect(sent.map((m) => m.op)).toEqual(['points', 'cancel']);
    expect(sent[1]).toEqual({ pageId: '7', draftId: 'd1', op: 'cancel' });
  });

  it('closes without a message when the connection is gone', () => {
    const { strokes, sent, sender } = setup();
    strokes.push({ c: 0, w: 2, p: [5, 5] });
    sender.close();
    sender.points(0);
    vi.advanceTimersByTime(1000);
    expect(sent).toEqual([]);
  });
});
