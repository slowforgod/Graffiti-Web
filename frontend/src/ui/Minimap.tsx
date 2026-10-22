import { useEffect, useRef } from 'react';
import type { Rect } from '../canvas/viewport';
import type { ClientConfig } from '../shared/pageConfig';

interface Props {
  config: Pick<ClientConfig, 'width' | 'height' | 'cellSize'>;
  cells: Uint8Array | null;
  view: Rect;
}

const FILLED = '#5C6BC0';

// Occupancy bitmap, one canvas pixel per cell, plus the rectangle the user is looking at (D-08).
export default function Minimap({ config, cells, view }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const cols = config.width / config.cellSize;
  const rows = config.height / config.cellSize;

  useEffect(() => {
    const ctx = canvas.current?.getContext('2d');
    if (!ctx) {
      return;
    }
    ctx.clearRect(0, 0, cols, rows);
    if (!cells) {
      return;
    }
    ctx.fillStyle = FILLED;
    for (let i = 0; i < cells.length; i++) {
      if (cells[i]) {
        ctx.fillRect(i % cols, Math.floor(i / cols), 1, 1);
      }
    }
  }, [cells, cols, rows]);

  const pct = (v: number, total: number) => `${Math.min(100, Math.max(0, (v / total) * 100))}%`;
  const left = Math.max(0, view.x);
  const top = Math.max(0, view.y);
  const right = Math.min(config.width, view.x + view.w);
  const bottom = Math.min(config.height, view.y + view.h);

  return (
    <div className="minimap" role="img" aria-label="미니맵">
      <canvas ref={canvas} width={cols} height={rows} />
      <div
        className="minimap-view"
        data-testid="minimap-view"
        style={{
          left: pct(left, config.width),
          top: pct(top, config.height),
          width: pct(Math.max(0, right - left), config.width),
          height: pct(Math.max(0, bottom - top), config.height),
        }}
      />
    </div>
  );
}
