// Size of a text doodle as Konva draws it (DoodleShape: line height 1). The server accepts at most
// `size × textWidthPercent% × longest line` wide (rounded up) and `size × 1.4 × lines` high (D-31, 13), so the
// measure never goes over.
export type MeasureLine = (line: string, size: number, family: string) => number | null;

let context: CanvasRenderingContext2D | null | undefined;

// Width from a 2D canvas, or null where there is none (tests, very old browsers).
export const canvasMeasure: MeasureLine = (line, size, family) => {
  if (context === undefined) {
    context = document.createElement('canvas').getContext('2d');
  }
  if (!context) {
    return null;
  }
  context.font = `${size}px ${family}`;
  return context.measureText(line).width;
};

export function measureText(
  text: string,
  size: number,
  family: string,
  widthPercent: number,
  measure: MeasureLine = canvasMeasure,
): { w: number; h: number } {
  const lines = text.split('\n');
  let w = 1;
  for (const line of lines) {
    const cap = Math.ceil((size * [...line].length * widthPercent) / 100);
    const measured = measure(line, size, family);
    w = Math.max(w, Math.min(cap, Math.ceil(measured ?? cap)));
  }
  return { w, h: size * lines.length };
}
