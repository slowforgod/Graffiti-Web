import { ApiError } from '../api/client';
import type { ClientConfig } from '../shared/pageConfig';
import type { Doodle } from './doodles';
import { DoodleStore } from './doodleStore';

export type FetchChunks = (pageId: string, chunks: number[]) => Promise<Doodle[]>;

interface Options {
  pageId: string;
  config: Pick<ClientConfig, 'width' | 'height' | 'chunkSize'>;
  fetchChunks: FetchChunks;
  onChange: (doodles: Doodle[]) => void;
  onPageEnded: () => void;
}

// Loads the doodles of the visible chunks (D-09): new chunks are fetched together, chunks that leave the
// view are dropped. A chunk whose fetch failed stays unloaded and is fetched again on the next view change.
export class ChunkLoader {
  readonly store: DoodleStore;
  private readonly options: Options;
  private visible = new Set<number>();
  private readonly inFlight = new Set<number>();
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
    const leaving = this.store.loadedChunks().filter((c) => !this.visible.has(c));
    if (leaving.length > 0) {
      this.store.dropChunks(leaving);
      this.options.onChange(this.store.list());
    }
    const entering = chunks.filter((c) => !this.store.isLoaded(c) && !this.inFlight.has(c));
    if (entering.length > 0) {
      void this.load(entering);
    }
  }

  // Stops applying responses and callbacks.
  dispose(): void {
    this.disposed = true;
  }

  private async load(chunks: number[]): Promise<void> {
    chunks.forEach((c) => this.inFlight.add(c));
    let doodles: Doodle[];
    try {
      doodles = await this.options.fetchChunks(this.options.pageId, chunks);
    } catch (error) {
      chunks.forEach((c) => this.inFlight.delete(c));
      if (!this.disposed && error instanceof ApiError && error.code === 'PAGE_ENDED') {
        this.options.onPageEnded();
      }
      return;
    }
    chunks.forEach((c) => this.inFlight.delete(c));
    if (this.disposed) {
      return;
    }
    // A chunk that left the view while the request was in flight is ignored.
    const wanted = chunks.filter((c) => this.visible.has(c));
    wanted.forEach((c) => this.store.loadChunk(c, doodles));
    if (wanted.length > 0) {
      this.options.onChange(this.store.list());
    }
  }
}
