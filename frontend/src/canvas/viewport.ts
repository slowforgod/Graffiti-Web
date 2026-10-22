import type { ClientConfig } from '../shared/pageConfig';

// Stage transform: screen = world * scale + (x, y), the same meaning as Konva's stage position and scale.
export interface Viewport {
  x: number;
  y: number;
  scale: number;
}

export interface Size {
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

type Bounds = Pick<ClientConfig, 'width' | 'height' | 'zoomMin' | 'zoomMax'>;

export function clampScale(scale: number, config: Bounds): number {
  return Math.min(config.zoomMax, Math.max(config.zoomMin, scale));
}

export function toWorld(vp: Viewport, screen: Point): Point {
  return { x: (screen.x - vp.x) / vp.scale, y: (screen.y - vp.y) / vp.scale };
}

// Keeps the world point at the centre of the screen inside the canvas, so the canvas never leaves the screen.
export function clampPosition(vp: Viewport, screen: Size, config: Bounds): Viewport {
  const center = toWorld(vp, { x: screen.width / 2, y: screen.height / 2 });
  const cx = Math.min(config.width, Math.max(0, center.x));
  const cy = Math.min(config.height, Math.max(0, center.y));
  return { scale: vp.scale, x: screen.width / 2 - cx * vp.scale, y: screen.height / 2 - cy * vp.scale };
}

export function centerOn(world: Point, scale: number, screen: Size, config: Bounds): Viewport {
  const s = clampScale(scale, config);
  return { scale: s, x: screen.width / 2 - world.x * s, y: screen.height / 2 - world.y * s };
}

// Zooms so that the world point under `pointer` stays under it.
export function zoomAt(vp: Viewport, pointer: Point, scale: number, screen: Size, config: Bounds): Viewport {
  const s = clampScale(scale, config);
  const anchor = toWorld(vp, pointer);
  return clampPosition({ scale: s, x: pointer.x - anchor.x * s, y: pointer.y - anchor.y * s }, screen, config);
}

export function visibleWorldRect(vp: Viewport, screen: Size): Rect {
  const topLeft = toWorld(vp, { x: 0, y: 0 });
  return { x: topLeft.x, y: topLeft.y, w: screen.width / vp.scale, h: screen.height / vp.scale };
}
