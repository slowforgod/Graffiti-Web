import type { Doodle } from '../canvas/doodles';
import type { Rect } from '../canvas/viewport';

// Empty space kept around the doodles in a saved picture.
export const PNG_MARGIN = 16;

// Behind the doodles, so that white or light ink still shows in an image viewer.
export const PNG_BACKGROUND = '#FFFFFF';

// The part of CanvasRenderingContext2D the picture needs, so tests can record the calls.
export type Painter = Pick<
  CanvasRenderingContext2D,
  | 'fillStyle'
  | 'strokeStyle'
  | 'lineWidth'
  | 'lineCap'
  | 'lineJoin'
  | 'font'
  | 'textBaseline'
  | 'beginPath'
  | 'moveTo'
  | 'lineTo'
  | 'stroke'
  | 'arc'
  | 'fill'
  | 'fillText'
  | 'fillRect'
>;

// The doodles' common box plus a margin, kept on the canvas. Null without doodles.
export function pictureBounds(doodles: Doodle[], canvas: { width: number; height: number }): Rect | null {
  if (doodles.length === 0) {
    return null;
  }
  const x0 = Math.max(0, Math.min(...doodles.map((d) => d.bbox.x)) - PNG_MARGIN);
  const y0 = Math.max(0, Math.min(...doodles.map((d) => d.bbox.y)) - PNG_MARGIN);
  const x1 = Math.min(canvas.width, Math.max(...doodles.map((d) => d.bbox.x + d.bbox.w)) + PNG_MARGIN);
  const y1 = Math.min(canvas.height, Math.max(...doodles.map((d) => d.bbox.y + d.bbox.h)) + PNG_MARGIN);
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
}

// Draws the doodles as the canvas shows them (DoodleShape): round strokes, a dot for one point, text lines
// in Konva's default font with a line height of one font size. `origin` is the picture's top left corner.
export function paintDoodles(ctx: Painter, doodles: Doodle[], palette: string[], origin: Rect): void {
  const color = (c: number) => palette[c] ?? palette[0]!;
  ctx.fillStyle = PNG_BACKGROUND;
  ctx.fillRect(0, 0, origin.w, origin.h);
  for (const d of doodles) {
    if (d.kind === 'TEXT') {
      const t = d.payload;
      ctx.fillStyle = color(t.c);
      ctx.font = `${t.size}px Arial`;
      ctx.textBaseline = 'top';
      t.text.split('\n').forEach((line, i) => ctx.fillText(line, t.x - origin.x, t.y - origin.y + i * t.size));
      continue;
    }
    for (const s of d.payload.strokes) {
      if (s.p.length === 2) {
        ctx.fillStyle = color(s.c);
        ctx.beginPath();
        ctx.arc(s.p[0]! - origin.x, s.p[1]! - origin.y, s.w / 2, 0, Math.PI * 2);
        ctx.fill();
        continue;
      }
      ctx.strokeStyle = color(s.c);
      ctx.lineWidth = s.w;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.beginPath();
      ctx.moveTo(s.p[0]! - origin.x, s.p[1]! - origin.y);
      for (let i = 2; i + 1 < s.p.length; i += 2) {
        ctx.lineTo(s.p[i]! - origin.x, s.p[i + 1]! - origin.y);
      }
      ctx.stroke();
    }
  }
}

// D-13: made in the browser only (canvas.toBlob), no server API. False when there is nothing to save.
export async function savePng(
  doodles: Doodle[],
  palette: string[],
  canvas: { width: number; height: number },
  filename: string,
): Promise<boolean> {
  const bounds = pictureBounds(doodles, canvas);
  if (!bounds) {
    return false;
  }
  const element = document.createElement('canvas');
  element.width = bounds.w;
  element.height = bounds.h;
  const ctx = element.getContext('2d');
  if (!ctx) {
    throw new Error('no 2d context');
  }
  paintDoodles(ctx, doodles, palette, bounds);
  const blob = await new Promise<Blob | null>((resolve) => element.toBlob(resolve, 'image/png'));
  if (!blob) {
    throw new Error('no picture');
  }
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  // Revoked after the click has started the download.
  setTimeout(() => URL.revokeObjectURL(url), 0);
  return true;
}
