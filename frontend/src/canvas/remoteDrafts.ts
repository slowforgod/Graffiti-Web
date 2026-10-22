import type { Rect } from './viewport';

// Chunk topic draft events (API.md §6). Points arrive in batches; a batch relayed to two chunks the
// viewer watches arrives twice.
export type DraftEvent =
  | { type: 'draft:update'; sid: string; draftId: string; op: 'points'; strokeIndex: number; c: number; w: number; points: number[] }
  | { type: 'draft:update'; sid: string; draftId: string; op: 'erase'; strokeIndex: number }
  | { type: 'draft:update'; sid: string; draftId: string; op: 'text'; bbox: Rect }
  | { type: 'draft:cancel'; sid: string; draftId: string };

// One batch of a stroke, drawn as its own line: batches a viewer missed (D-29) leave a gap, not a jump.
export interface RemoteSegment {
  c: number;
  w: number;
  p: number[];
}

export interface RemoteDraft {
  draftId: string;
  segments: RemoteSegment[];
  box: Rect | null;
}

interface Entry {
  draftId: string;
  strokes: Map<number, { segments: RemoteSegment[]; keys: Set<string> }>;
  erased: Set<number>;
  box: Rect | null;
  chunks: Set<number>;
  seenAt: number;
}

// Other people's drafts as this viewer has seen them. Nothing is kept once they are cancelled,
// silent for the idle timeout, or out of view (INV-06).
export class RemoteDrafts {
  private readonly byId = new Map<string, Entry>();

  apply(chunk: number, event: DraftEvent, now: number): void {
    if (event.type === 'draft:cancel') {
      this.byId.delete(event.draftId);
      return;
    }
    let entry = this.byId.get(event.draftId);
    if (!entry) {
      entry = { draftId: event.draftId, strokes: new Map(), erased: new Set(), box: null, chunks: new Set(), seenAt: now };
      this.byId.set(event.draftId, entry);
    }
    entry.chunks.add(chunk);
    entry.seenAt = now;
    if (event.op === 'text') {
      entry.box = event.bbox;
    } else if (event.op === 'erase') {
      // Remembered so a batch of the stroke that arrives late does not bring it back.
      entry.erased.add(event.strokeIndex);
      entry.strokes.delete(event.strokeIndex);
    } else if (!entry.erased.has(event.strokeIndex)) {
      const stroke = entry.strokes.get(event.strokeIndex) ?? { segments: [], keys: new Set<string>() };
      entry.strokes.set(event.strokeIndex, stroke);
      const p = event.points;
      const key = `${p.length}:${p[0]},${p[1]}:${p[p.length - 2]},${p[p.length - 1]}`;
      if (!stroke.keys.has(key)) {
        stroke.keys.add(key);
        stroke.segments.push({ c: event.c, w: event.w, p });
      }
    }
  }

  // Drafts last heard of before `now - maxAgeMs` are dropped: their author went quiet (D-11).
  expire(now: number, maxAgeMs: number): boolean {
    let changed = false;
    for (const [id, entry] of this.byId) {
      if (now - entry.seenAt >= maxAgeMs) {
        this.byId.delete(id);
        changed = true;
      }
    }
    return changed;
  }

  dropChunks(chunks: Iterable<number>): void {
    for (const chunk of chunks) {
      for (const [id, entry] of this.byId) {
        entry.chunks.delete(chunk);
        if (entry.chunks.size === 0) {
          this.byId.delete(id);
        }
      }
    }
  }

  clear(): void {
    this.byId.clear();
  }

  list(): RemoteDraft[] {
    return [...this.byId.values()].map((e) => ({
      draftId: e.draftId,
      segments: [...e.strokes.entries()].sort((a, b) => a[0] - b[0]).flatMap(([, s]) => s.segments),
      box: e.box,
    }));
  }
}
