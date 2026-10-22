import { apiFetch } from '../api/client';
import type { Rect } from './viewport';

// API.md §2 / DB.md §3. Coordinates are world pixels, colours are palette indices.
export interface Stroke {
  c: number;
  w: number;
  p: number[];
}

export type Doodle =
  | { id: string; kind: 'DRAWING'; bbox: Rect; payload: { v: number; strokes: Stroke[] } }
  | {
      id: string;
      kind: 'TEXT';
      bbox: Rect;
      payload: { v: number; text: string; c: number; size: number; x: number; y: number };
    };

export async function fetchChunkDoodles(pageId: string, chunks: number[]): Promise<Doodle[]> {
  const query = encodeURIComponent(chunks.join(','));
  const body = await apiFetch<{ doodles: Doodle[] }>(`/api/pages/${encodeURIComponent(pageId)}/doodles?chunks=${query}`);
  return body.doodles;
}
