import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { FakeLink } from '../realtime/fakeLink';
import { chunkTopic } from '../realtime/link';
import { ChunkSync } from './chunkSync';
import type { RemoteCursor } from './cursors';
import type { Doodle } from './doodles';
import { TEST_CONFIG } from './testConfig';

// Chunk 0 is the top-left 480×480 square, chunk 1 the one to its right.
function doodle(id: string, x = 10, y = 10): Doodle {
  return { id, kind: 'DRAWING', bbox: { x, y, w: 10, h: 10 }, payload: { v: 1, strokes: [] } };
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function setup(connected = true) {
  const link = new FakeLink();
  link.isConnected = connected;
  const answers: ReturnType<typeof deferred<Doodle[]>>[] = [];
  const fetchChunks = vi.fn<(page: string, chunks: number[]) => Promise<Doodle[]>>(() => {
    const d = deferred<Doodle[]>();
    answers.push(d);
    return d.promise;
  });
  let doodles: Doodle[] = [];
  let cursors: RemoteCursor[] = [];
  const onPageEnded = vi.fn();
  const sync = new ChunkSync({
    pageId: '7',
    config: TEST_CONFIG,
    link,
    fetchChunks,
    onDoodles: (d) => (doodles = d),
    onCursors: (c) => (cursors = c),
    onPageEnded,
  });
  return { link, sync, fetchChunks, answers, onPageEnded, ids: () => doodles.map((d) => d.id), cursors: () => cursors };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('ChunkSync', () => {
  it('subscribes first and queries only after every receipt (INV-09)', async () => {
    const { link, sync, fetchChunks, answers, ids } = setup();
    sync.setVisible([0, 1]);

    expect(link.active().map((s) => s.destination)).toEqual([chunkTopic('7', 0), chunkTopic('7', 1)]);
    expect(fetchChunks).not.toHaveBeenCalled();

    link.readyAll();
    expect(fetchChunks).toHaveBeenCalledWith('7', [0, 1]);
    answers[0]!.resolve([doodle('1')]);
    await flush();
    expect(ids()).toEqual(['1']);
  });

  it('replays events that arrived between subscribing and the answer', async () => {
    const { link, sync, answers, ids } = setup();
    sync.setVisible([0]);
    link.readyAll();
    link.deliver(chunkTopic('7', 0), { type: 'doodle:published', doodle: doodle('2') });
    link.deliver(chunkTopic('7', 0), { type: 'doodle:removed', id: '1' });
    expect(ids()).toEqual([]);

    // The answer was read before the removal of 1 and the publication of 2.
    answers[0]!.resolve([doodle('1')]);
    await flush();
    expect(ids()).toEqual(['2']);

    link.deliver(chunkTopic('7', 0), { type: 'doodle:published', doodle: doodle('3') });
    expect(ids()).toEqual(['2', '3']);
  });

  it('shows cursors at once and drops them with their chunk', () => {
    const { link, sync, cursors } = setup();
    sync.setVisible([0, 1]);
    link.deliver(chunkTopic('7', 0), { type: 'cursor', sid: 's-a', nickname: 'A', color: '#000', x: 5, y: 5 });
    expect(cursors().map((c) => c.sid)).toEqual(['s-a']);

    sync.setVisible([1]);
    expect(cursors()).toEqual([]);
  });

  it('ignores a leave from a chunk the cursor already left', () => {
    const { link, sync, cursors } = setup();
    sync.setVisible([0, 1]);
    link.deliver(chunkTopic('7', 1), { type: 'cursor', sid: 's-a', nickname: 'A', color: '#000', x: 500, y: 5 });
    link.deliver(chunkTopic('7', 0), { type: 'cursor:leave', sid: 's-a' });
    expect(cursors()).toHaveLength(1);
    link.deliver(chunkTopic('7', 1), { type: 'cursor:leave', sid: 's-a' });
    expect(cursors()).toEqual([]);
  });

  it('unsubscribes from chunks that leave the view and forgets their doodles', async () => {
    const { link, sync, answers, ids } = setup();
    sync.setVisible([0, 1]);
    link.readyAll();
    answers[0]!.resolve([doodle('1'), doodle('2', 500, 10)]);
    await flush();

    sync.setVisible([1]);
    expect(link.active().map((s) => s.destination)).toEqual([chunkTopic('7', 1)]);
    expect(ids()).toEqual(['2']);
  });

  it('does not query a batch whose chunks all left before the receipts', () => {
    const { link, sync, fetchChunks } = setup();
    sync.setVisible([0]);
    const first = link.active()[0]!;
    sync.setVisible([1]);
    first.onReady();
    expect(fetchChunks).not.toHaveBeenCalled();
  });

  it('queries without subscribing while disconnected, then resyncs on connect', async () => {
    const { link, sync, fetchChunks, answers, ids } = setup(false);
    sync.setVisible([0]);
    expect(link.subscriptions).toEqual([]);
    answers[0]!.resolve([doodle('1')]);
    await flush();
    expect(ids()).toEqual(['1']);

    link.isConnected = true;
    sync.resync();
    expect(link.active().map((s) => s.destination)).toEqual([chunkTopic('7', 0)]);
    link.readyAll();
    expect(fetchChunks).toHaveBeenCalledTimes(2);
    answers[1]!.resolve([]);
    await flush();
    expect(ids()).toEqual([]);
  });

  it('reports PAGE_ENDED and can retry the chunk later', async () => {
    const { link, sync, answers, onPageEnded } = setup();
    sync.setVisible([0]);
    link.readyAll();
    answers[0]!.reject(new ApiError(409, 'PAGE_ENDED', ''));
    await flush();
    expect(onPageEnded).toHaveBeenCalled();
    expect(link.active()).toEqual([]);
  });
});
