import { describe, expect, it } from 'vitest';
import type { Doodle } from '../canvas/doodles';
import type { Painter } from './png';
import { PNG_BACKGROUND, PNG_MARGIN, paintDoodles, pictureBounds } from './png';

const palette = ['#000000', '#FFFFFF', '#E53935'];

function recorder(): { ctx: Painter; calls: string[] } {
  const calls: string[] = [];
  const state: Record<string, unknown> = {};
  const record =
    (name: string) =>
    (...args: unknown[]) => {
      calls.push(`${name}(${args.join(',')})`);
    };
  const ctx = new Proxy(state, {
    get: (target, key: string) => (key in target ? target[key] : record(key)),
    set: (target, key: string, value) => {
      target[key] = value;
      calls.push(`${key}=${String(value)}`);
      return true;
    },
  }) as unknown as Painter;
  return { ctx, calls };
}

const line: Doodle = {
  id: '1',
  kind: 'DRAWING',
  bbox: { x: 96, y: 196, w: 28, h: 18 },
  payload: { v: 1, strokes: [{ c: 2, w: 8, p: [100, 200, 110, 205, 120, 210] }] },
};
const dot: Doodle = { id: '2', kind: 'DRAWING', bbox: { x: 296, y: 296, w: 8, h: 8 }, payload: { v: 1, strokes: [{ c: 0, w: 8, p: [300, 300] }] } };
const text: Doodle = {
  id: '3',
  kind: 'TEXT',
  bbox: { x: 50, y: 400, w: 48, h: 48 },
  payload: { v: 1, text: '안녕\n하세요', c: 1, size: 24, x: 50, y: 400 },
};

describe('pictureBounds', () => {
  it('covers every doodle with a margin, kept on the canvas', () => {
    expect(pictureBounds([line, dot], { width: 7680, height: 4320 })).toEqual({
      x: 96 - PNG_MARGIN,
      y: 196 - PNG_MARGIN,
      w: 304 - 96 + 2 * PNG_MARGIN,
      h: 304 - 196 + 2 * PNG_MARGIN,
    });
    const corner: Doodle = { ...dot, bbox: { x: 0, y: 0, w: 8, h: 8 } };
    expect(pictureBounds([corner], { width: 20, height: 20 })).toEqual({ x: 0, y: 0, w: 20, h: 20 });
    expect(pictureBounds([], { width: 20, height: 20 })).toBeNull();
  });
});

describe('paintDoodles', () => {
  it('fills the background and draws strokes, dots and text lines relative to the picture', () => {
    const { ctx, calls } = recorder();

    paintDoodles(ctx, [line, dot, text], palette, { x: 40, y: 180, w: 300, h: 300 });

    expect(calls.slice(0, 2)).toEqual([`fillStyle=${PNG_BACKGROUND}`, 'fillRect(0,0,300,300)']);
    expect(calls).toContain('strokeStyle=#E53935');
    expect(calls).toContain('lineCap=round');
    expect(calls).toContain('moveTo(60,20)');
    expect(calls).toContain('lineTo(80,30)');
    expect(calls).toContain(`arc(260,120,4,0,${Math.PI * 2})`);
    // A text from before fonts is drawn in the first font (13).
    expect(calls).toContain('font=24px gf-pretendard, sans-serif');
    expect(calls).toContain('fillText(안녕,10,220)');
    expect(calls).toContain('fillText(하세요,10,244)');
  });

  it('draws a text in its own font', () => {
    const { ctx, calls } = recorder();
    const jalnan: Doodle = { ...text, payload: { ...text.payload, f: 'jalnan' } };

    paintDoodles(ctx, [jalnan], palette, { x: 40, y: 380, w: 100, h: 100 });

    expect(calls).toContain('font=24px gf-jalnan, sans-serif');
  });
});
