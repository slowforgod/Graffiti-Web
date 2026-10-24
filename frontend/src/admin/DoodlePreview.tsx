import { Layer, Rect, Stage } from 'react-konva';
import DoodleShape from '../canvas/DoodleShape';
import type { Doodle } from '../canvas/doodles';

interface Props {
  doodle: Doodle;
  palette: string[];
  maxSize: number;
}

// Draws one doodle scaled into a small box. Text goes through Konva too, never the DOM (SEC-12).
export default function DoodlePreview({ doodle, palette, maxSize }: Props) {
  const { x, y, w, h } = doodle.bbox;
  const scale = Math.min(1, maxSize / Math.max(w, h));
  const width = Math.max(1, Math.round(w * scale));
  const height = Math.max(1, Math.round(h * scale));
  return (
    <Stage width={width} height={height} listening={false}>
      <Layer scaleX={scale} scaleY={scale} x={-x * scale} y={-y * scale}>
        <Rect x={x} y={y} width={w} height={h} fill="#F5F5F5" />
        <DoodleShape doodle={doodle} palette={palette} />
      </Layer>
    </Stage>
  );
}
