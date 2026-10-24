import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Doodle } from '../canvas/doodles';
import { TEST_CONFIG } from '../canvas/testConfig';
import { deleteMine, fetchMine, loadDoodles } from './myDoodles';

function drawing(id: string, x: number, y: number): Doodle {
  return { id, kind: 'DRAWING', bbox: { x, y, w: 10, h: 10 }, payload: { v: 1, strokes: [{ c: 0, w: 2, p: [x + 1, y + 1] }] } };
}

describe('my doodles API', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=t1';
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  it('lists the doodles of a page', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ pageId: '3', doodles: [{ id: '9', bbox: { x: 1, y: 2, w: 3, h: 4 } }] })));

    expect(await fetchMine('3')).toEqual([{ id: '9', bbox: { x: 1, y: 2, w: 3, h: 4 } }]);
    expect(String(fetchMock.mock.calls[0]![0])).toBe('/api/me/doodles?pageId=3');
  });

  it('deletes one doodle with DELETE and several with the batch endpoint, with the CSRF token', async () => {
    fetchMock.mockImplementation(async () => new Response(null, { status: 204 }));

    await deleteMine(['9']);
    await deleteMine(['9', '10']);

    const [one, many] = fetchMock.mock.calls;
    expect(String(one![0])).toBe('/api/doodles/9');
    expect(one![1]!.method).toBe('DELETE');
    expect(new Headers(one![1]!.headers).get('X-XSRF-TOKEN')).toBe('t1');
    expect(String(many![0])).toBe('/api/doodles/delete');
    expect(many![1]!.method).toBe('POST');
    expect(JSON.parse(String(many![1]!.body))).toEqual({ ids: ['9', '10'] });
  });
});

describe('loadDoodles', () => {
  it('reads the chunks under the doodles in groups of maxChunks and keeps only the wanted ones', async () => {
    const config = { ...TEST_CONFIG, maxChunks: 2 };
    // Chunks 0, 1 and 17 (480px chunks, 16 per row).
    const items = [
      { id: '1', bbox: { x: 10, y: 10, w: 10, h: 10 } },
      { id: '2', bbox: { x: 500, y: 10, w: 10, h: 10 } },
      { id: '3', bbox: { x: 500, y: 500, w: 10, h: 10 } },
    ];
    const fetchChunks = vi.fn(async (_page: string, chunks: number[]) => {
      const all: Record<number, Doodle[]> = {
        0: [drawing('1', 10, 10), drawing('99', 20, 20)],
        1: [drawing('2', 500, 10)],
        17: [drawing('3', 500, 500)],
      };
      return chunks.flatMap((c) => all[c] ?? []);
    });

    const doodles = await loadDoodles('3', items, config, fetchChunks);

    expect(fetchChunks.mock.calls.map((c) => c[1])).toEqual([[0, 1], [17]]);
    expect(doodles.map((d) => d.id)).toEqual(['1', '2', '3']);
  });
});
