import { describe, expect, it } from 'vitest';
import { TEST_CONFIG as config } from './testConfig';
import { centerOn, clampPosition, clampScale, toWorld, visibleWorldRect, zoomAt } from './viewport';

const screen = { width: 1920, height: 1080 };

describe('viewport', () => {
  it('limits the scale to the configured zoom range (D-05)', () => {
    expect(clampScale(0.1, config)).toBe(0.5);
    expect(clampScale(9, config)).toBe(4);
    expect(clampScale(1.5, config)).toBe(1.5);
  });

  it('keeps the world point under the pointer while zooming', () => {
    const vp = centerOn({ x: 3840, y: 2160 }, 1, screen, config);
    const pointer = { x: 700, y: 300 };
    const before = toWorld(vp, pointer);

    const zoomed = zoomAt(vp, pointer, 2, screen, config);

    expect(zoomed.scale).toBe(2);
    expect(toWorld(zoomed, pointer).x).toBeCloseTo(before.x);
    expect(toWorld(zoomed, pointer).y).toBeCloseTo(before.y);
  });

  it('stops zooming at the limits', () => {
    const vp = centerOn({ x: 3840, y: 2160 }, 1, screen, config);
    expect(zoomAt(vp, { x: 960, y: 540 }, 100, screen, config).scale).toBe(4);
    expect(zoomAt(vp, { x: 960, y: 540 }, 0.01, screen, config).scale).toBe(0.5);
  });

  it('keeps the screen centre inside the canvas when panning far away', () => {
    const far = clampPosition({ x: 100_000, y: -100_000, scale: 1 }, screen, config);
    const center = toWorld(far, { x: 960, y: 540 });
    expect(center).toEqual({ x: 0, y: config.height });
  });

  it('shows a quarter of the canvas at 0.5x on a 1920x1080 screen (D-05)', () => {
    const rect = visibleWorldRect(centerOn({ x: 3840, y: 2160 }, 0.5, screen, config), screen);
    expect((rect.w * rect.h) / (config.width * config.height)).toBeCloseTo(0.25);
  });
});
