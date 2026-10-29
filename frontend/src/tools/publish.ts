import { apiFetch } from '../api/client';
import type { Doodle, Stroke } from '../canvas/doodles';
import type { Draft } from './draft';
import { isEmpty, liveStrokes } from './draft';

// POST /api/pages/{pageId}/doodles (API.md §3). The server works out a drawing's bbox from its points;
// a text sends the size it was measured at (D-31).
export type PublishBody =
  | { kind: 'DRAWING'; payload: { v: 1; strokes: Stroke[] } }
  | {
      kind: 'TEXT';
      payload: { v: 1; text: string; c: number; size: number; x: number; y: number };
      bbox: { w: number; h: number };
    };

// shrunk: the text had profanity and went up at the smallest size (D-43).
export interface Published {
  doodle: Doodle;
  cooldownEndsAt: string | null;
  shrunk?: boolean;
}

// 202: stored, but no real check ran, so an admin looks at it before it goes on the canvas.
export interface Held {
  held: true;
  cooldownEndsAt: string | null;
}

export function isHeld(result: Published | Held): result is Held {
  return 'held' in result && result.held;
}

// Null for a draft with nothing to post yet; the server refuses blank texts too.
export function publishBody(draft: Draft): PublishBody | null {
  if (isEmpty(draft) || (draft.kind === 'TEXT' && draft.text.trim() === '')) {
    return null;
  }
  if (draft.kind === 'TEXT') {
    const { text, c, size, x, y, w, h } = draft;
    return { kind: 'TEXT', payload: { v: 1, text, c, size, x, y }, bbox: { w, h } };
  }
  return { kind: 'DRAWING', payload: { v: 1, strokes: liveStrokes(draft).map(({ c, w, p }) => ({ c, w, p: [...p] })) } };
}

export function publishDoodle(pageId: string, body: PublishBody): Promise<Published | Held> {
  return apiFetch<Published | Held>(`/api/pages/${encodeURIComponent(pageId)}/doodles`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
