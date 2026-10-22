import type Konva from 'konva';
import { useEffect, useRef, useState } from 'react';
import { Layer, Rect, Stage } from 'react-konva';
import type { ClientConfig } from '../shared/pageConfig';
import type { Draft } from '../tools/draft';
import type { Tool } from '../tools/useDraft';
import CursorLayer from './CursorLayer';
import type { RemoteCursor } from './cursors';
import DoodleShape from './DoodleShape';
import DraftLayer from './DraftLayer';
import GridBackground from './GridBackground';
import type { Doodle } from './doodles';
import type { RemoteDraft } from './remoteDrafts';
import type { Point, Rect as Box, Size, Viewport } from './viewport';
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
  // Pen, text and eraser take one finger or the mouse; `move` (or holding space) pans instead (D-07).
  tool?: Tool;
  ownDraft?: Draft | null;
  // The dashed square of the largest drawing, shown with the pen.
  sizeGuide?: Box | null;
  otherDrafts?: RemoteDraft[];
  onDrawStart?: (world: Point, scale: number) => void;
  onDrawMove?: (world: Point, scale: number) => void;
  onDrawEnd?: () => void;
}

// One wheel notch zooms by this factor.
const WHEEL_ZOOM_STEP = 1.1;

interface Pinch {
  center: Point;
  distance: number;
}

// Two fingers always pan and zoom, the wheel zooms. One finger or the mouse follows the tool (D-07).
export default function CanvasView({
  config,
  size,
  viewport,
  doodles,
  cursors = [],
  onViewportChange,
  onPointerMove,
  tool = 'move',
  ownDraft = null,
  sizeGuide = null,
  otherDrafts = [],
  onDrawStart,
  onDrawMove,
  onDrawEnd,
}: Props) {
  const pinch = useRef<Pinch | null>(null);
  const drawing = useRef<number | null>(null);
  const space = useSpaceHeld();
  const view = visibleWorldRect(viewport, size);
  const pans = tool === 'move' || space;

  const worldOf = (stage: Konva.Stage | null): Point | null => {
    const pointer = stage?.getPointerPosition();
    if (!pointer) {
      return null;
    }
    const world = toWorld(viewport, pointer);
    return { x: Math.floor(world.x), y: Math.floor(world.y) };
  };

  const endDraw = () => {
    if (drawing.current !== null) {
      drawing.current = null;
      onDrawEnd?.();
    }
  };

  const onPointerDown = (e: Konva.KonvaEventObject<PointerEvent>) => {
    if (pans || drawing.current !== null || pinch.current || e.evt.button > 0) {
      return;
    }
    const world = worldOf(e.target.getStage());
    if (world) {
      drawing.current = e.evt.pointerId;
      onDrawStart?.(world, viewport.scale);
    }
  };

  const onPointerMoveDraw = (e: Konva.KonvaEventObject<PointerEvent>) => {
    if (drawing.current === null || e.evt.pointerId !== drawing.current) {
      return;
    }
    const world = worldOf(e.target.getStage());
    if (world) {
      onDrawMove?.(world, viewport.scale);
    }
  };

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
    // A second finger ends the stroke in progress: two fingers always move the view.
    endDraw();
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
      draggable={pans}
      onWheel={onWheel}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMoveDraw}
      onPointerUp={endDraw}
      onPointerCancel={endDraw}
      onPointerLeave={endDraw}
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
        {sizeGuide && (
          <Rect
            x={sizeGuide.x}
            y={sizeGuide.y}
            width={sizeGuide.w}
            height={sizeGuide.h}
            stroke="#000000"
            strokeWidth={1.5 / viewport.scale}
            dash={[8 / viewport.scale, 6 / viewport.scale]}
          />
        )}
        <DraftLayer own={ownDraft} others={otherDrafts} palette={config.palette} scale={viewport.scale} />
        <CursorLayer cursors={cursors} scale={viewport.scale} />
      </Layer>
    </Stage>
  );
}

// Holding space pans with any tool (D-07), except while typing a text draft.
function useSpaceHeld(): boolean {
  const [held, setHeld] = useState(false);
  useEffect(() => {
    const typing = (e: KeyboardEvent) => e.target instanceof HTMLTextAreaElement || e.target instanceof HTMLInputElement;
    const down = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !typing(e)) {
        e.preventDefault();
        setHeld(true);
      }
    };
    const up = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        setHeld(false);
      }
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
    };
  }, []);
  return held;
}
