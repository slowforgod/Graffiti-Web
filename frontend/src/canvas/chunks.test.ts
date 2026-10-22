import { describe, expect, it } from 'vitest';
import { chunksOfRect, visibleChunks } from './chunks';
import { TEST_CONFIG as config } from './testConfig';
import { centerOn, visibleWorldRect } from './viewport';

describe('chunks', () => {
  it('numbers chunks row by row, 16 per row (D-06)', () => {
    expect(chunksOfRect({ x: 0, y: 0, w: 10, h: 10 }, config)).toEqual([0]);
    expect(chunksOfRect({ x: 7679, y: 4319, w: 1, h: 1 }, config)).toEqual([143]);
    expect(chunksOfRect({ x: 470, y: 470, w: 20, h: 20 }, config)).toEqual([0, 1, 16, 17]);
  });

  it('treats the right and bottom edge as exclusive', () => {
    expect(chunksOfRect({ x: 0, y: 0, w: 480, h: 480 }, config)).toEqual([0]);
  });

  it('clips to the canvas', () => {
    expect(chunksOfRect({ x: -500, y: -500, w: 600, h: 600 }, config)).toEqual([0]);
    expect(chunksOfRect({ x: 9000, y: 0, w: 10, h: 10 }, config)).toEqual([]);
  });

  it('needs at most 60 chunks for a 1920x1080 screen at 0.5x', () => {
    const screen = { width: 1920, height: 1080 };
    for (const [x, y] of [
      [3840, 2160],
      [1000, 1000],
      [3600, 2100],
    ] as const) {
      const view = visibleWorldRect(centerOn({ x, y }, 0.5, screen, config), screen);
      const chunks = visibleChunks(view, config);
      expect(chunks).toEqual([...new Set(chunksOfRect(view, config))].sort((a, b) => distanceOrder(a, b, view)));
      expect(chunks.length).toBeLessThanOrEqual(60);
    }
  });

  it('keeps the 60 chunks nearest the centre on a large screen (D-30)', () => {
    const screen = { width: 3840, height: 2160 };
    const view = visibleWorldRect(centerOn({ x: 3840, y: 2160 }, 0.5, screen, config), screen);
    const all = chunksOfRect(view, config);
    const chunks = visibleChunks(view, config);

    expect(all.length).toBeGreaterThan(60);
    expect(chunks).toHaveLength(60);
    // The centre chunks are kept, a corner chunk is not.
    expect(chunks).toContain(4 * 16 + 7);
    expect(chunks).not.toContain(0);
  });
});

function distanceOrder(a: number, b: number, view: { x: number; y: number; w: number; h: number }): number {
  const cx = view.x + view.w / 2;
  const cy = view.y + view.h / 2;
  const d = (i: number) => (((i % 16) + 0.5) * 480 - cx) ** 2 + ((Math.floor(i / 16) + 0.5) * 480 - cy) ** 2;
  return d(a) - d(b) || a - b;
}
