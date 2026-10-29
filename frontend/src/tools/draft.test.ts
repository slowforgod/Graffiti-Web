import { describe, expect, it } from 'vitest';
import {
  draftBounds,
  eraseAt,
  extendStroke,
  isEmpty,
  liveStrokes,
  moveDraft,
  newDrawing,
  startStroke,
  strokeBounds,
  textLength,
} from './draft';
import { measureText } from './textMeasure';

const LIMITS = { width: 7680, height: 4320, maxBbox: 1024 };

describe('drawing drafts', () => {
  it('drops points under 1px from the previous one (D-11)', () => {
    const d = newDrawing('a');
    const i = startStroke(d, { x: 10, y: 10 }, 3, 4, LIMITS)!;
    expect(extendStroke(d, i, { x: 10, y: 10 }, LIMITS)).toBe('too-close');
    expect(extendStroke(d, i, { x: 11, y: 10 }, LIMITS)).toBe('added');
    expect(d.strokes[i]).toEqual({ c: 3, w: 4, p: [10, 10, 11, 10] });
  });

  it('keeps the whole draft, stroke width included, within the bbox limit', () => {
    const d = newDrawing('a');
    const i = startStroke(d, { x: 100, y: 100 }, 0, 4, LIMITS)!;
    // 98 .. 1122 is exactly 1024 wide.
    expect(extendStroke(d, i, { x: 1120, y: 100 }, LIMITS)).toBe('added');
    expect(extendStroke(d, i, { x: 1121, y: 100 }, LIMITS)).toBe('too-big');
    expect(startStroke(d, { x: 50, y: 50 }, 0, 4, LIMITS)).toBeNull();
    expect(draftBounds(d)).toEqual({ x: 98, y: 98, w: 1024, h: 4 });
  });

  it('refuses points outside the canvas', () => {
    const d = newDrawing('a');
    expect(startStroke(d, { x: -1, y: 0 }, 0, 4, LIMITS)).toBeNull();
    const i = startStroke(d, { x: 7679, y: 4319 }, 0, 4, LIMITS)!;
    expect(extendStroke(d, i, { x: 7680, y: 4319 }, LIMITS)).toBe('outside');
  });

  it('erases every touched stroke whole and keeps the other indices', () => {
    const d = newDrawing('a');
    startStroke(d, { x: 0, y: 0 }, 0, 2, LIMITS);
    extendStroke(d, 0, { x: 100, y: 0 }, LIMITS);
    startStroke(d, { x: 0, y: 50 }, 0, 2, LIMITS);
    startStroke(d, { x: 50, y: 6 }, 0, 4, LIMITS);

    expect(eraseAt(d, { x: 50, y: 4 }, 3)).toEqual([0, 2]);
    expect(d.strokes[0]).toBeNull();
    expect(liveStrokes(d)).toEqual([{ c: 0, w: 2, p: [0, 50] }]);
    expect(eraseAt(d, { x: 300, y: 300 }, 3)).toEqual([]);
    eraseAt(d, { x: 0, y: 50 }, 1);
    expect(isEmpty(d)).toBe(true);
  });

  it('measures a stroke like the server footprint: half the width on every side', () => {
    expect(strokeBounds({ c: 0, w: 8, p: [10, 2, 20, 30] })).toEqual({ x: 6, y: -2, w: 18, h: 36 });
  });
});

describe('text drafts', () => {
  it('counts letters without line breaks (D-07)', () => {
    expect(textLength('안녕\n경희대!')).toBe(6);
    expect(textLength('😀')).toBe(1);
  });

  it('measures within the server cap, falling back to it without a canvas (D-31)', () => {
    expect(measureText('ab\nabcd', 10, () => null)).toEqual({ w: 40, h: 20 });
    expect(measureText('abcd', 10, (line) => line.length * 5.2)).toEqual({ w: 21, h: 10 });
    expect(measureText('ab', 10, () => 99)).toEqual({ w: 20, h: 10 });
    expect(measureText('', 24, () => 0)).toEqual({ w: 1, h: 24 });
  });
});

describe('moving a draft', () => {
  it('shifts every stroke by whole pixels', () => {
    const d = newDrawing('a');
    const i = startStroke(d, { x: 100, y: 100 }, 0, 4, LIMITS)!;
    extendStroke(d, i, { x: 110, y: 105 }, LIMITS);
    expect(moveDraft(d, 20.4, -9.6, LIMITS)).toEqual({ x: 20, y: -10 });
    expect(d.strokes[i]!.p).toEqual([120, 90, 130, 95]);
  });

  it('stops at the canvas edge', () => {
    const d = newDrawing('a');
    startStroke(d, { x: 10, y: 10 }, 0, 4, LIMITS);
    // The bbox starts at 8 (half the width), so it can go 8px left and no further.
    expect(moveDraft(d, -50, 0, LIMITS)).toEqual({ x: -8, y: 0 });
    expect(draftBounds(d)!.x).toBe(0);
    expect(moveDraft(d, -5, 0, LIMITS)).toBeNull();
  });

  it('moves a text by its corner', () => {
    const t = { id: 't', kind: 'TEXT' as const, text: 'hi', c: 0, size: 24, x: 7600, y: 50, w: 60, h: 30 };
    moveDraft(t, 100, 10, LIMITS);
    expect(t).toMatchObject({ x: 7620, y: 60 });
  });
});
