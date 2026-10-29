import { describe, expect, it } from 'vitest';
import type { DrawingDraft, TextDraft } from './draft';
import { publishBody } from './publish';

describe('publishBody', () => {
  it('sends the drawing strokes that were not erased, without a bbox', () => {
    const draft: DrawingDraft = {
      id: 'd',
      kind: 'DRAWING',
      strokes: [{ c: 3, w: 8, p: [1, 2, 3, 4] }, null, { c: 0, w: 2, p: [9, 9] }],
    };
    expect(publishBody(draft)).toEqual({
      kind: 'DRAWING',
      payload: { v: 1, strokes: [{ c: 3, w: 8, p: [1, 2, 3, 4] }, { c: 0, w: 2, p: [9, 9] }] },
    });
  });

  it('sends a text with its measured size and its font', () => {
    const draft: TextDraft = { id: 't', kind: 'TEXT', text: '안녕', c: 2, size: 24, f: 'jalnan', x: 10, y: 20, w: 48, h: 24 };
    expect(publishBody(draft)).toEqual({
      kind: 'TEXT',
      payload: { v: 1, text: '안녕', c: 2, size: 24, x: 10, y: 20, f: 'jalnan' },
      bbox: { w: 48, h: 24 },
    });
  });

  it('has nothing to send for an empty draft', () => {
    expect(publishBody({ id: 'd', kind: 'DRAWING', strokes: [null] })).toBeNull();
    expect(publishBody({ id: 't', kind: 'TEXT', text: '', c: 0, size: 24, f: 'pretendard', x: 0, y: 0, w: 1, h: 24 })).toBeNull();
    expect(publishBody({ id: 't', kind: 'TEXT', text: ' \n ', c: 0, size: 24, f: 'pretendard', x: 0, y: 0, w: 6, h: 48 })).toBeNull();
  });
});
