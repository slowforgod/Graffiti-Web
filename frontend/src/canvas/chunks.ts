import type { ClientConfig } from '../shared/pageConfig';
import type { Rect } from './viewport';

type Grid = Pick<ClientConfig, 'width' | 'height' | 'chunkSize'>;

export function chunkCols(config: Grid): number {
  return config.width / config.chunkSize;
}

export function chunkRows(config: Grid): number {
  return config.height / config.chunkSize;
}

// Chunk index = row * cols + col (DB.md doodle_chunk). Returns [] for a rect fully outside the canvas.
function chunkRange(rect: Rect, config: Grid): { c0: number; c1: number; r0: number; r1: number } | null {
  const cols = chunkCols(config);
  const rows = chunkRows(config);
  const c0 = Math.max(0, Math.floor(rect.x / config.chunkSize));
  const r0 = Math.max(0, Math.floor(rect.y / config.chunkSize));
  // The right and bottom edges are exclusive, so a rect ending exactly on a chunk border stays out of the next chunk.
  const c1 = Math.min(cols - 1, Math.ceil((rect.x + rect.w) / config.chunkSize) - 1);
  const r1 = Math.min(rows - 1, Math.ceil((rect.y + rect.h) / config.chunkSize) - 1);
  if (c0 > c1 || r0 > r1) {
    return null;
  }
  return { c0, c1, r0, r1 };
}

export function chunksOfRect(rect: Rect, config: Grid): number[] {
  const range = chunkRange(rect, config);
  if (range === null) {
    return [];
  }
  const cols = chunkCols(config);
  const result: number[] = [];
  for (let r = range.r0; r <= range.r1; r++) {
    for (let c = range.c0; c <= range.c1; c++) {
      result.push(r * cols + c);
    }
  }
  return result;
}

// Chunks the view touches, nearest to the view centre first, at most `maxChunks` (D-30).
export function visibleChunks(view: Rect, config: Grid & Pick<ClientConfig, 'maxChunks'>): number[] {
  const cols = chunkCols(config);
  const cx = view.x + view.w / 2;
  const cy = view.y + view.h / 2;
  const distance = (index: number) => {
    const mx = ((index % cols) + 0.5) * config.chunkSize - cx;
    const my = (Math.floor(index / cols) + 0.5) * config.chunkSize - cy;
    return mx * mx + my * my;
  };
  return chunksOfRect(view, config)
    .map((index) => ({ index, d: distance(index) }))
    .sort((a, b) => a.d - b.d || a.index - b.index)
    .slice(0, config.maxChunks)
    .map((e) => e.index);
}
