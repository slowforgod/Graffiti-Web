import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { Doodle } from './doodles';
import { ChunkLoader } from './chunkLoader';
import { TEST_CONFIG as config } from './testConfig';

function drawing(id: string, x: number, y: number): Doodle {
  return { id, kind: 'DRAWING', bbox: { x, y, w: 10, h: 10 }, payload: { v: 1, strokes: [{ c: 0, w: 2, p: [x, y] }] } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function setup() {
  const requests: { chunks: number[]; reply: ReturnType<typeof deferred<Doodle[]>> }[] = [];
  const fetchChunks = vi.fn((_pageId: string, chunks: number[]) => {
    const reply = deferred<Doodle[]>();
    requests.push({ chunks, reply });
    return reply.promise;
  });
  const onChange = vi.fn<(doodles: Doodle[]) => void>();
  const onPageEnded = vi.fn();
  const loader = new ChunkLoader({ pageId: '7', config, fetchChunks, onChange, onPageEnded });
  return { loader, requests, fetchChunks, onChange, onPageEnded };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('ChunkLoader', () => {
  it('fetches only chunks that are new and not already requested', async () => {
    const { loader, requests, fetchChunks } = setup();
    loader.setVisible([0, 1]);
    loader.setVisible([0, 1, 2]);
    expect(fetchChunks.mock.calls.map((c) => c[1])).toEqual([[0, 1], [2]]);

    requests[0]!.reply.resolve([]);
    requests[1]!.reply.resolve([]);
    await flush();
    loader.setVisible([1, 2, 3]);
    expect(fetchChunks.mock.calls.at(-1)?.[1]).toEqual([3]);
  });

  it('shows the doodles of loaded chunks and drops them when the chunk leaves', async () => {
    const { loader, requests, onChange } = setup();
    loader.setVisible([0]);
    requests[0]!.reply.resolve([drawing('1', 10, 10)]);
    await flush();
    expect(onChange).toHaveBeenLastCalledWith([drawing('1', 10, 10)]);

    loader.setVisible([5]);
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  it('ignores a response for a chunk that left the view meanwhile', async () => {
    const { loader, requests, onChange } = setup();
    loader.setVisible([0]);
    loader.setVisible([5]);
    requests[0]!.reply.resolve([drawing('1', 10, 10)]);
    await flush();
    expect(onChange).not.toHaveBeenCalled();
    expect(loader.store.isLoaded(0)).toBe(false);
  });

  it('retries a failed chunk on the next view change', async () => {
    const { loader, requests, fetchChunks } = setup();
    loader.setVisible([0]);
    requests[0]!.reply.reject(new TypeError('network'));
    await flush();
    expect(loader.store.isLoaded(0)).toBe(false);

    loader.setVisible([0]);
    expect(fetchChunks).toHaveBeenCalledTimes(2);
  });

  it('reports a turned page', async () => {
    const { loader, requests, onPageEnded } = setup();
    loader.setVisible([0]);
    requests[0]!.reply.reject(new ApiError(409, 'PAGE_ENDED', ''));
    await flush();
    expect(onPageEnded).toHaveBeenCalledOnce();
  });

  it('does nothing after dispose', async () => {
    const { loader, requests, onChange, onPageEnded } = setup();
    loader.setVisible([0, 1]);
    loader.dispose();
    requests[0]!.reply.reject(new ApiError(409, 'PAGE_ENDED', ''));
    await flush();
    loader.setVisible([2]);
    expect(onChange).not.toHaveBeenCalled();
    expect(onPageEnded).not.toHaveBeenCalled();
    expect(requests).toHaveLength(1);
  });
});
