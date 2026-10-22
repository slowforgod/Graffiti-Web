// Size of a text doodle as Konva draws it (DoodleShape: default font, line height 1). The server accepts
// at most `size × longest line` wide and `size × 1.4 × lines` high (D-31), so the measure never goes over.
export const TEXT_FONT = 'Arial';

export type MeasureLine = (line: string, size: number) => number | null;

let context: CanvasRenderingContext2D | null | undefined;

// Width from a 2D canvas, or null where there is none (tests, very old browsers).
export const canvasMeasure: MeasureLine = (line, size) => {
  if (context === undefined) {
    context = document.createElement('canvas').getContext('2d');
  }
  if (!context) {
    return null;
  }
  context.font = `${size}px ${TEXT_FONT}`;
  return context.measureText(line).width;
};

export function measureText(text: string, size: number, measure: MeasureLine = canvasMeasure): { w: number; h: number } {
  const lines = text.split('\n');
  let w = 1;
  for (const line of lines) {
    const cap = size * [...line].length;
    const measured = measure(line, size);
    w = Math.max(w, Math.min(cap, Math.ceil(measured ?? cap)));
  }
  return { w, h: size * lines.length };
}
