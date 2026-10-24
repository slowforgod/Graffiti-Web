import { ApiError } from '../api/client';
import type { RealtimeLink } from '../realtime/link';
import { chunkTopic } from '../realtime/link';
import type { ClientConfig } from '../shared/pageConfig';
import type { RemoteCursor } from './cursors';
import { CursorBoard } from './cursors';
import type { Doodle } from './doodles';
import { DoodleStore } from './doodleStore';
import type { DraftEvent, RemoteDraft } from './remoteDrafts';
import { RemoteDrafts } from './remoteDrafts';

export type FetchChunks = (pageId: string, chunks: number[]) => Promise<Doodle[]>;

// Chunk topic events of API.md §6; unknown types are ignored.
export type ChunkEvent =
  | DraftEvent
  | { type: 'doodle:published'; doodle: Doodle }
  | { type: 'doodle:removed'; id: string }
  | { type: 'cursor'; sid: string; nickname: string; color: string; x: number; y: number }
  | { type: 'cursor:leave'; sid: string }
  | { type: string };

interface Options {
  pageId: string;
  config: Pick<ClientConfig, 'width' | 'height' | 'chunkSize'>;
  link: RealtimeLink;
  fetchChunks: FetchChunks;
  onDoodles: (doodles: Doodle[]) => void;
  onCursors: (cursors: RemoteCursor[]) => void;
  onDrafts?: (drafts: RemoteDraft[]) => void;
  onPageEnded: () => void;
  now?: () => number;
}

interface ChunkState {
  live: boolean;
  buffer: ChunkEvent[];
  unsubscribe: () => void;
}

// Keeps the visible chunks in sync (D-09, INV-09): subscribe first, query REST once the server has
// registered the subscriptions, apply the answer, then replay what arrived meanwhile. Without a
// connection it only queries, and catches up with `resync` when the connection comes (back).
export class ChunkSync {
  readonly store: DoodleStore;
  readonly cursors = new CursorBoard();
  readonly drafts = new RemoteDrafts();
  private readonly options: Options;
  private visible = new Set<number>();
  private readonly states = new Map<number, ChunkState>();
  private readonly fetching = new Set<number>();
  private disposed = false;

  constructor(options: Options) {
    this.options = options;
    this.store = new DoodleStore(options.config);
  }

  setVisible(chunks: number[]): void {
    if (this.disposed) {
      return;
    }
    this.visible = new Set(chunks);
    const leaving = new Set([...this.states.keys(), ...this.store.loadedChunks()].filter((c) => !this.visible.has(c)));
    if (leaving.size > 0) {
      leaving.forEach((c) => this.forget(c));
      this.store.dropChunks(leaving);
      this.cursors.dropChunks(leaving);
      this.drafts.dropChunks(leaving);
      this.emit();
    }
    const connected = this.options.link.connected();
    const entering = chunks.filter(
      (c) => !this.states.has(c) && !this.fetching.has(c) && (connected || !this.store.isLoaded(c)),
    );
    if (entering.length === 0) {
      return;
    }
    if (connected) {
      this.subscribeThenFetch(entering);
    } else {
      void this.fetch(entering);
    }
  }

  // After a (re)connect every subscription is new: subscribe and query all visible chunks again.
  resync(): void {
    if (this.disposed) {
      return;
    }
    this.states.clear();
    this.cursors.clear();
    this.drafts.clear();
    this.setVisible([...this.visible]);
    this.emit();
  }

  // Drops other people's drafts not heard of for `maxAgeMs` (D-11: idle drafts are cancelled).
  expireDrafts(maxAgeMs: number): void {
    if (this.drafts.expire(this.now(), maxAgeMs)) {
      this.emit();
    }
  }

  // The author's own doodle, shown at once; the doodle:published that follows is the same id.
  addPublished(doodle: Doodle): void {
    this.store.put(doodle);
    this.emit();
  }

  // The author's own deleted doodles, gone at once; the doodle:removed that follows finds nothing.
  removeOwn(ids: string[]): void {
    ids.forEach((id) => this.store.remove(id));
    this.emit();
  }

  dispose(): void {
    this.disposed = true;
    this.states.forEach((s) => s.unsubscribe());
    this.states.clear();
  }

  private subscribeThenFetch(chunks: number[]): void {
    const batch = new Map<number, ChunkState>();
    const waiting = new Set(chunks);
    for (const chunk of chunks) {
      const state: ChunkState = { live: false, buffer: [], unsubscribe: () => {} };
      batch.set(chunk, state);
      this.states.set(chunk, state);
      state.unsubscribe = this.options.link.subscribe(
        chunkTopic(this.options.pageId, chunk),
        (body) => this.onEvent(chunk, body as ChunkEvent),
        () => {
          waiting.delete(chunk);
          if (waiting.size === 0 && !this.disposed) {
            // Only chunks still subscribed by this batch: others left the view or were resynced.
            const current = chunks.filter((c) => this.states.get(c) === batch.get(c));
            if (current.length > 0) {
              void this.fetch(current);
            }
          }
        },
      );
    }
  }

  private async fetch(chunks: number[]): Promise<void> {
    chunks.forEach((c) => this.fetching.add(c));
    let doodles: Doodle[];
    try {
      doodles = await this.options.fetchChunks(this.options.pageId, chunks);
    } catch (error) {
      chunks.forEach((c) => {
        this.fetching.delete(c);
        this.forget(c);
      });
      if (!this.disposed && error instanceof ApiError && error.code === 'PAGE_ENDED') {
        this.options.onPageEnded();
      }
      return;
    }
    chunks.forEach((c) => this.fetching.delete(c));
    if (this.disposed) {
      return;
    }
    let changed = false;
    for (const chunk of chunks.filter((c) => this.visible.has(c))) {
      this.store.loadChunk(chunk, doodles);
      const state = this.states.get(chunk);
      if (state) {
        state.buffer.forEach((e) => this.apply(chunk, e));
        state.buffer = [];
        state.live = true;
      }
      changed = true;
    }
    if (changed) {
      this.emit();
    }
    // Fetched without a connection, which has come up meanwhile: subscribe now so no event is missed.
    const unsubscribed = chunks.filter((c) => this.visible.has(c) && !this.states.has(c));
    if (unsubscribed.length > 0 && this.options.link.connected()) {
      this.subscribeThenFetch(unsubscribed);
    }
  }

  private onEvent(chunk: number, event: ChunkEvent): void {
    const state = this.states.get(chunk);
    if (this.disposed || !state) {
      return;
    }
    // Cursors and drafts are live only: the REST answer does not carry them, so they are never buffered.
    if (event.type === 'cursor' || event.type === 'cursor:leave') {
      this.apply(chunk, event);
    } else if (event.type === 'draft:update' || event.type === 'draft:cancel') {
      this.drafts.apply(chunk, event as DraftEvent, this.now());
    } else if (state.live) {
      this.apply(chunk, event);
    } else {
      state.buffer.push(event);
      return;
    }
    this.emit();
  }

  private apply(chunk: number, event: ChunkEvent): void {
    if (isType(event, 'doodle:published')) {
      this.store.put(event.doodle);
    } else if (isType(event, 'doodle:removed')) {
      this.store.remove(event.id);
    } else if (isType(event, 'cursor')) {
      const { sid, nickname, color, x, y } = event;
      this.cursors.move({ sid, nickname, color, x, y, chunk });
    } else if (isType(event, 'cursor:leave')) {
      this.cursors.leave(event.sid, chunk);
    }
  }

  private forget(chunk: number): void {
    this.states.get(chunk)?.unsubscribe();
    this.states.delete(chunk);
  }

  private emit(): void {
    this.options.onDoodles(this.store.list());
    this.options.onCursors(this.cursors.list());
    this.options.onDrafts?.(this.drafts.list());
  }

  private now(): number {
    return (this.options.now ?? Date.now)();
  }
}

function isType<T extends ChunkEvent['type']>(event: ChunkEvent, type: T): event is Extract<ChunkEvent, { type: T }> {
  return event.type === type;
}
