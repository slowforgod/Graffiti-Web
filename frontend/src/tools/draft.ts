import type { Point, Rect } from '../canvas/viewport';

// The author's own draft (D-10, D-11). It lives only in this screen: nothing is stored (INV-06).
// A drawing keeps erased strokes as null so stroke indices stay the ones already sent.
export interface DraftStroke {
  c: number;
  w: number;
  p: number[];
}

export interface DrawingDraft {
  id: string;
  kind: 'DRAWING';
  strokes: (DraftStroke | null)[];
}

export interface TextDraft {
  id: string;
  kind: 'TEXT';
  text: string;
  c: number;
  size: number;
  // Font id (13).
  f: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export type Draft = DrawingDraft | TextDraft;

interface Limits {
  width: number;
  height: number;
  maxBbox: number;
}

// Points closer than this to the previous one are dropped (D-11).
const MIN_STEP = 1;

export function newDrawing(id: string): DrawingDraft {
  return { id, kind: 'DRAWING', strokes: [] };
}

export function inCanvas(p: Point, limits: Limits): boolean {
  return p.x >= 0 && p.y >= 0 && p.x < limits.width && p.y < limits.height;
}

// The area a stroke covers: its points widened by half the width, as the server counts it (D-14).
export function strokeBounds(s: DraftStroke): Rect {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (let i = 0; i + 1 < s.p.length; i += 2) {
    minX = Math.min(minX, s.p[i]!);
    minY = Math.min(minY, s.p[i + 1]!);
    maxX = Math.max(maxX, s.p[i]!);
    maxY = Math.max(maxY, s.p[i + 1]!);
  }
  const half = s.w / 2;
  const x = Math.floor(minX - half);
  const y = Math.floor(minY - half);
  return { x, y, w: Math.ceil(maxX + half) - x, h: Math.ceil(maxY + half) - y };
}

export function union(a: Rect | null, b: Rect): Rect {
  if (!a) {
    return b;
  }
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return { x, y, w: Math.max(a.x + a.w, b.x + b.w) - x, h: Math.max(a.y + a.h, b.y + b.h) - y };
}

export function draftBounds(draft: Draft): Rect | null {
  if (draft.kind === 'TEXT') {
    return { x: draft.x, y: draft.y, w: draft.w, h: draft.h };
  }
  return draft.strokes.reduce<Rect | null>((acc, s) => (s ? union(acc, strokeBounds(s)) : acc), null);
}

function fits(rect: Rect, limits: Limits): boolean {
  return rect.w <= limits.maxBbox && rect.h <= limits.maxBbox;
}

// Starts a stroke at `p`. Returns its index, or null when the dot alone would leave the bbox limit.
export function startStroke(draft: DrawingDraft, p: Point, c: number, w: number, limits: Limits): number | null {
  const stroke = { c, w, p: [p.x, p.y] };
  if (!inCanvas(p, limits) || !fits(union(draftBounds(draft), strokeBounds(stroke)), limits)) {
    return null;
  }
  draft.strokes.push(stroke);
  return draft.strokes.length - 1;
}

export type Extend = 'added' | 'too-close' | 'too-big' | 'outside';

// Adds `p` to the last stroke unless it is under 1px from the previous point or would make the draft
// larger than the bbox limit (D-11).
export function extendStroke(draft: DrawingDraft, index: number, p: Point, limits: Limits): Extend {
  const stroke = draft.strokes[index];
  if (!stroke) {
    return 'outside';
  }
  if (!inCanvas(p, limits)) {
    return 'outside';
  }
  const lx = stroke.p[stroke.p.length - 2]!;
  const ly = stroke.p[stroke.p.length - 1]!;
  if (Math.hypot(p.x - lx, p.y - ly) < MIN_STEP) {
    return 'too-close';
  }
  const half = stroke.w / 2;
  const dot = { x: Math.floor(p.x - half), y: Math.floor(p.y - half), w: Math.ceil(stroke.w), h: Math.ceil(stroke.w) };
  if (!fits(union(draftBounds(draft), dot), limits)) {
    return 'too-big';
  }
  stroke.p.push(p.x, p.y);
  return 'added';
}

// Distance from p to the segment ab.
function segmentDistance(p: Point, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax;
  const dy = by - ay;
  const length = dx * dx + dy * dy;
  const t = length === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - ax) * dx + (p.y - ay) * dy) / length));
  return Math.hypot(p.x - (ax + t * dx), p.y - (ay + t * dy));
}

// Erases every draft stroke the eraser of `radius` touches at `p`, whole (D-11). Returns their indices.
export function eraseAt(draft: DrawingDraft, p: Point, radius: number): number[] {
  const erased: number[] = [];
  draft.strokes.forEach((s, i) => {
    if (!s) {
      return;
    }
    const reach = radius + s.w / 2;
    let hit = s.p.length === 2 && Math.hypot(p.x - s.p[0]!, p.y - s.p[1]!) <= reach;
    for (let j = 0; !hit && j + 3 < s.p.length; j += 2) {
      hit = segmentDistance(p, s.p[j]!, s.p[j + 1]!, s.p[j + 2]!, s.p[j + 3]!) <= reach;
    }
    if (hit) {
      draft.strokes[i] = null;
      erased.push(i);
    }
  });
  return erased;
}

// Moves the whole draft by (dx, dy), rounded to whole pixels and cut short so it stays on the canvas.
// Returns how far it moved, or null when it could not move at all.
export function moveDraft(draft: Draft, dx: number, dy: number, limits: Limits): Point | null {
  const bounds = draftBounds(draft);
  if (!bounds || !Number.isFinite(dx) || !Number.isFinite(dy)) {
    return null;
  }
  const mx = Math.round(Math.min(Math.max(dx, -bounds.x), limits.width - bounds.x - bounds.w));
  const my = Math.round(Math.min(Math.max(dy, -bounds.y), limits.height - bounds.y - bounds.h));
  if (mx === 0 && my === 0) {
    return null;
  }
  if (draft.kind === 'TEXT') {
    draft.x += mx;
    draft.y += my;
    return { x: mx, y: my };
  }
  for (const s of draft.strokes) {
    for (let i = 0; s && i + 1 < s.p.length; i += 2) {
      s.p[i]! += mx;
      s.p[i + 1]! += my;
    }
  }
  return { x: mx, y: my };
}

export function liveStrokes(draft: DrawingDraft): DraftStroke[] {
  return draft.strokes.filter((s): s is DraftStroke => s !== null);
}

export function isEmpty(draft: Draft): boolean {
  return draft.kind === 'TEXT' ? textLength(draft.text) === 0 : liveStrokes(draft).length === 0;
}

// Letters counted against the text limit: line breaks do not count (D-07).
export function textLength(text: string): number {
  return [...text.replaceAll('\n', '')].length;
}
