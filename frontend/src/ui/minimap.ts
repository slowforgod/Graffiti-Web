import { apiFetch } from '../api/client';

export interface MinimapBody {
  cols: number;
  rows: number;
  bitmap: string;
  occupancyPermille: number;
}

export function fetchMinimap(pageId: string): Promise<MinimapBody> {
  return apiFetch<MinimapBody>(`/api/pages/${encodeURIComponent(pageId)}/minimap`);
}

// API.md §2: bit for cell `row * cols + col` is in byte index / 8, most significant bit first.
export function decodeBitmap(base64: string, cellCount: number): Uint8Array {
  const raw = atob(base64);
  const cells = new Uint8Array(cellCount);
  for (let i = 0; i < cellCount; i++) {
    const byte = raw.charCodeAt(i >> 3);
    cells[i] = (byte >> (7 - (i & 7))) & 1;
  }
  return cells;
}

