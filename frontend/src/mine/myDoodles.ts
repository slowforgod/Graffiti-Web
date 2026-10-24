import { apiFetch } from '../api/client';
import { chunksOfRect } from '../canvas/chunks';
import type { Doodle } from '../canvas/doodles';
import { fetchChunkDoodles } from '../canvas/doodles';
import type { Rect } from '../canvas/viewport';
import type { ClientConfig } from '../shared/pageConfig';

// One of the author's published doodles on the page (GET /api/me/doodles, D-13).
export interface MineItem {
  id: string;
  bbox: Rect;
}

export async function fetchMine(pageId: string): Promise<MineItem[]> {
  const body = await apiFetch<{ doodles: MineItem[] }>(`/api/me/doodles?pageId=${encodeURIComponent(pageId)}`);
  return body.doodles;
}

// All or none (API.md §3). One id uses DELETE, several the batch endpoint.
export async function deleteMine(ids: string[]): Promise<void> {
  if (ids.length === 1) {
    await apiFetch<void>(`/api/doodles/${encodeURIComponent(ids[0]!)}`, { method: 'DELETE' });
    return;
  }
  await apiFetch<void>('/api/doodles/delete', { method: 'POST', body: JSON.stringify({ ids }) });
}

type FetchChunks = (pageId: string, chunks: number[]) => Promise<Doodle[]>;

// The full doodles behind `items`, read through the public chunk lookup: the list only says where they are.
export async function loadDoodles(
  pageId: string,
  items: MineItem[],
  config: Pick<ClientConfig, 'width' | 'height' | 'chunkSize' | 'maxChunks'>,
  fetchChunks: FetchChunks = fetchChunkDoodles,
): Promise<Doodle[]> {
  const wanted = new Set(items.map((i) => i.id));
  const chunks = [...new Set(items.flatMap((i) => chunksOfRect(i.bbox, config)))].sort((a, b) => a - b);
  const found = new Map<string, Doodle>();
  for (let start = 0; start < chunks.length; start += config.maxChunks) {
    const doodles = await fetchChunks(pageId, chunks.slice(start, start + config.maxChunks));
    doodles.filter((d) => wanted.has(d.id)).forEach((d) => found.set(d.id, d));
  }
  return items.map((i) => found.get(i.id)).filter((d): d is Doodle => d !== undefined);
}
