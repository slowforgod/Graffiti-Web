import type Konva from 'konva';
import { useRef } from 'react';
import { Layer, Stage } from 'react-konva';
import type { ClientConfig } from '../shared/pageConfig';
import CursorLayer from './CursorLayer';
import type { RemoteCursor } from './cursors';
import DoodleShape from './DoodleShape';
import GridBackground from './GridBackground';
import type { Doodle } from './doodles';
import type { Point, Size, Viewport } from './viewport';
import { clampPosition, clampScale, toWorld, visibleWorldRect, zoomAt } from './viewport';

interface Props {
  config: ClientConfig;
  size: Size;
  viewport: Viewport;
  doodles: Doodle[];
  cursors?: RemoteCursor[];
  onViewportChange: (viewport: Viewport) => void;
  // The pointer's position in canvas coordinates, while it is over the canvas.
  onPointerMove?: (world: Point) => void;
}

// One wheel notch zooms by this factor.
const WHEEL_ZOOM_STEP = 1.1;

interface Pinch {
  center: Point;
  distance: number;
}

// 03 has no drawing tools, so a one-finger or mouse drag always pans (D-07); two fingers pan and zoom.
export default function CanvasView({
  config,
  size,
  viewport,
  doodles,
  cursors = [],
  onViewportChange,
  onPointerMove,
}: Props) {
  const pinch = useRef<Pinch | null>(null);
  const view = visibleWorldRect(viewport, size);

  const onWheel = (e: Konva.KonvaEventObject<WheelEvent>) => {
    e.evt.preventDefault();
    const pointer = e.target.getStage()?.getPointerPosition();
    if (!pointer) {
      return;
    }
    const factor = e.evt.deltaY < 0 ? WHEEL_ZOOM_STEP : 1 / WHEEL_ZOOM_STEP;
    onViewportChange(zoomAt(viewport, pointer, viewport.scale * factor, size, config));
  };

  const onDragMove = (e: Konva.KonvaEventObject<DragEvent>) => {
    const stage = e.target;
    if (stage !== stage.getStage()) {
      return;
    }
    const next = clampPosition({ x: stage.x(), y: stage.y(), scale: viewport.scale }, size, config);
    stage.position({ x: next.x, y: next.y });
    onViewportChange(next);
  };

  const onTouchMove = (e: Konva.KonvaEventObject<TouchEvent>) => {
    const touches = e.evt.touches;
    const stage = e.target.getStage();
    if (touches.length !== 2 || !stage) {
      return;
    }
    e.evt.preventDefault();
    stage.stopDrag();
    const box = stage.container().getBoundingClientRect();
    const a = { x: touches[0]!.clientX - box.left, y: touches[0]!.clientY - box.top };
    const b = { x: touches[1]!.clientX - box.left, y: touches[1]!.clientY - box.top };
    const now = { center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, distance: Math.hypot(a.x - b.x, a.y - b.y) };
    const last = pinch.current;
    pinch.current = now;
    if (!last || last.distance === 0) {
      return;
    }
    const anchor = toWorld(viewport, last.center);
    const scale = clampScale(viewport.scale * (now.distance / last.distance), config);
    const moved = { scale, x: now.center.x - anchor.x * scale, y: now.center.y - anchor.y * scale };
    onViewportChange(clampPosition(moved, size, config));
  };

  const onMouseMove = (e: Konva.KonvaEventObject<MouseEvent>) => {
    const pointer = e.target.getStage()?.getPointerPosition();
    if (!pointer || !onPointerMove) {
      return;
    }
    const world = toWorld(viewport, pointer);
    if (world.x >= 0 && world.y >= 0 && world.x < config.width && world.y < config.height) {
      onPointerMove({ x: Math.floor(world.x), y: Math.floor(world.y) });
    }
  };

  return (
    <Stage
      width={size.width}
      height={size.height}
      x={viewport.x}
      y={viewport.y}
      scaleX={viewport.scale}
      scaleY={viewport.scale}
      draggable
      onWheel={onWheel}
      onDragMove={onDragMove}
      onTouchMove={onTouchMove}
      onMouseMove={onMouseMove}
      onTouchEnd={() => {
        pinch.current = null;
      }}
    >
      <Layer listening={false}>
        <GridBackground config={config} view={view} scale={viewport.scale} />
      </Layer>
      <Layer listening={false}>
        {doodles.map((d) => (
          <DoodleShape key={d.id} doodle={d} palette={config.palette} />
        ))}
      </Layer>
      <Layer listening={false}>
        <CursorLayer cursors={cursors} scale={viewport.scale} />
      </Layer>
    </Stage>
  );
}
