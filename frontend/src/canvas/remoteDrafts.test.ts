import { describe, expect, it } from 'vitest';
import { RemoteDrafts } from './remoteDrafts';

const points = (draftId: string, strokeIndex: number, p: number[]) =>
  ({ type: 'draft:update', sid: 's-1', draftId, op: 'points', strokeIndex, c: 2, w: 4, points: p }) as const;

describe('RemoteDrafts', () => {
  it('keeps each batch as its own segment and ignores the copy relayed to a second chunk', () => {
    const drafts = new RemoteDrafts();
    drafts.apply(0, points('a', 0, [1, 1, 2, 2]), 0);
    drafts.apply(1, points('a', 0, [1, 1, 2, 2]), 0);
    drafts.apply(0, points('a', 0, [2, 2, 3, 3]), 0);
    drafts.apply(0, points('a', 1, [9, 9]), 0);

    expect(drafts.list()).toEqual([
      {
        draftId: 'a',
        box: null,
        segments: [
          { c: 2, w: 4, p: [1, 1, 2, 2] },
          { c: 2, w: 4, p: [2, 2, 3, 3] },
          { c: 2, w: 4, p: [9, 9] },
        ],
      },
    ]);
  });

  it('erases a stroke for good and cancels a draft', () => {
    const drafts = new RemoteDrafts();
    drafts.apply(0, points('a', 0, [1, 1]), 0);
    drafts.apply(0, { type: 'draft:update', sid: 's-1', draftId: 'a', op: 'erase', strokeIndex: 0 }, 0);
    drafts.apply(0, points('a', 0, [5, 5]), 0);
    expect(drafts.list()[0]!.segments).toEqual([]);

    drafts.apply(0, { type: 'draft:cancel', sid: 's-1', draftId: 'a' }, 0);
    expect(drafts.list()).toEqual([]);
  });

  it('shows a text draft as its box only', () => {
    const drafts = new RemoteDrafts();
    drafts.apply(0, { type: 'draft:update', sid: 's-1', draftId: 't', op: 'text', bbox: { x: 1, y: 2, w: 30, h: 24 } }, 0);
    expect(drafts.list()).toEqual([{ draftId: 't', segments: [], box: { x: 1, y: 2, w: 30, h: 24 } }]);
  });

  it('forgets drafts that went quiet for the idle timeout (D-11)', () => {
    const drafts = new RemoteDrafts();
    drafts.apply(0, points('old', 0, [1, 1]), 0);
    drafts.apply(0, points('new', 0, [1, 1]), 500);
    expect(drafts.expire(599, 600)).toBe(false);
    expect(drafts.expire(600, 600)).toBe(true);
    expect(drafts.list().map((d) => d.draftId)).toEqual(['new']);
  });

  it('drops drafts seen only in chunks that left the view', () => {
    const drafts = new RemoteDrafts();
    drafts.apply(0, points('a', 0, [1, 1]), 0);
    drafts.apply(1, points('a', 0, [600, 1]), 0);
    drafts.apply(1, points('b', 0, [700, 1]), 0);
    drafts.dropChunks([1]);
    expect(drafts.list().map((d) => d.draftId)).toEqual(['a']);
  });
});
