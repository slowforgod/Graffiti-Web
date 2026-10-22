import { Rect, Shape } from 'react-konva';
import type { ClientConfig } from '../shared/pageConfig';
import type { Rect as WorldRect } from './viewport';

interface Props {
  config: Pick<ClientConfig, 'width' | 'height' | 'cellSize'>;
  view: WorldRect;
  scale: number;
}

const GRID_COLOR = '#ECEFF3';

// White page with a faint cell grid (D-07). Only the lines inside the view are drawn.
export default function GridBackground({ config, view, scale }: Props) {
  const { width, height, cellSize } = config;
  return (
    <>
      <Rect x={0} y={0} width={width} height={height} fill="#FFFFFF" listening={false} />
      <Shape
        listening={false}
        stroke={GRID_COLOR}
        strokeWidth={1 / scale}
        sceneFunc={(ctx, shape) => {
          const x0 = Math.max(0, Math.floor(view.x / cellSize) * cellSize);
          const y0 = Math.max(0, Math.floor(view.y / cellSize) * cellSize);
          const x1 = Math.min(width, view.x + view.w);
          const y1 = Math.min(height, view.y + view.h);
          ctx.beginPath();
          for (let x = x0; x <= x1; x += cellSize) {
            ctx.moveTo(x, Math.max(0, view.y));
            ctx.lineTo(x, y1);
          }
          for (let y = y0; y <= y1; y += cellSize) {
            ctx.moveTo(Math.max(0, view.x), y);
            ctx.lineTo(x1, y);
          }
          ctx.strokeShape(shape);
        }}
      />
    </>
  );
}
