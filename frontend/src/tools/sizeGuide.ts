import type { Rect } from '../canvas/viewport';

interface Limits {
  width: number;
  height: number;
  maxBbox: number;
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

// The dashed square that shows how big one doodle may be (maxBbox, D-11). Before the first stroke it
// sits in the middle of the view. Once the drawing has strokes it stays where it was and moves only as
// far as needed to keep the drawing inside, which is always possible since the drawing fits maxBbox.
export function sizeGuide(prev: Rect | null, bounds: Rect | null, view: Rect, limits: Limits): Rect {
  const size = limits.maxBbox;
  let x = prev && bounds ? prev.x : Math.round(view.x + view.w / 2 - size / 2);
  let y = prev && bounds ? prev.y : Math.round(view.y + view.h / 2 - size / 2);
  x = clamp(x, 0, Math.max(0, limits.width - size));
  y = clamp(y, 0, Math.max(0, limits.height - size));
  if (bounds) {
    x = clamp(x, bounds.x + bounds.w - size, bounds.x);
    y = clamp(y, bounds.y + bounds.h - size, bounds.y);
  }
  return { x, y, w: size, h: size };
}
