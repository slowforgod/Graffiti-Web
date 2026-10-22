import { describe, expect, it } from 'vitest';
import type { Doodle } from './doodles';
import { compareIds, DoodleStore } from './doodleStore';
import { TEST_CONFIG as config } from './testConfig';

function drawing(id: string, x: number, y: number, w = 10, h = 10): Doodle {
  return { id, kind: 'DRAWING', bbox: { x, y, w, h }, payload: { v: 1, strokes: [{ c: 0, w: 2, p: [x, y] }] } };
}

describe('DoodleStore', () => {
  it('keeps a doodle spanning two chunks until both are dropped', () => {
    const store = new DoodleStore(config);
    const spanning = drawing('1', 470, 10, 20);
    store.loadChunk(0, [spanning]);
    store.loadChunk(1, [spanning]);

    store.dropChunks([0]);
    expect(store.list()).toEqual([spanning]);

    store.dropChunks([1]);
    expect(store.list()).toEqual([]);
  });

  it('ignores doodles of a response that do not touch the loaded chunk', () => {
    const store = new DoodleStore(config);
    store.loadChunk(0, [drawing('1', 10, 10), drawing('2', 2000, 10)]);
    expect(store.list().map((d) => d.id)).toEqual(['1']);
  });

  it('accepts live doodles only in loaded chunks', () => {
    const store = new DoodleStore(config);
    store.loadChunk(0, []);
    store.put(drawing('5', 10, 10));
    store.put(drawing('6', 2000, 10));
    expect(store.list().map((d) => d.id)).toEqual(['5']);
    store.remove('5');
    expect(store.list()).toEqual([]);
  });

  it('lists in id order, comparing ids as integers', () => {
    const store = new DoodleStore(config);
    store.loadChunk(0, [drawing('10', 1, 1), drawing('9', 1, 1), drawing('100', 1, 1)]);
    expect(store.list().map((d) => d.id)).toEqual(['9', '10', '100']);
    expect(compareIds('9007199254740993', '9007199254740992')).toBeGreaterThan(0);
  });
});
