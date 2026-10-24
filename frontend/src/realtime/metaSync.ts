import { ApiError } from '../api/client';
import type { PageStatus } from '../shared/pageConfig';
import type { MinimapBody } from '../ui/minimapApi';
import { decodeBitmap } from '../ui/minimapApi';
import type { RealtimeLink } from './link';
import { metaTopic } from './link';

export interface PageStatusEvent {
  type: 'page:status';
  status: PageStatus;
  occupancyPermille: number;
  countdownStartedAt: string | null;
  scheduledEndAt: string;
}

export type MetaEvent =
  | PageStatusEvent
  | { type: 'minimap:diff'; set: number[]; clear: number[]; occupancyPermille: number }
  | { type: 'page:turned'; newPageId: string }
  | { type: string };

export interface PageMeta {
  cells: Uint8Array;
  occupancyPermille: number;
}

interface Options {
  pageId: string;
  link: RealtimeLink;
  fetchMinimap: (pageId: string) => Promise<MinimapBody>;
  onMeta: (meta: PageMeta) => void;
  // page:status, as it arrives (D-16: the status bar and the countdown follow it).
  onStatus?: (status: PageStatusEvent) => void;
  onPageTurned: () => void;
  onPageEnded: () => void;
}

// The page's meta topic, loaded like a chunk (INV-09): subscribe, fetch the minimap once the
// subscription is registered, then replay buffered diffs. Diffs set or clear absolute cells, so
// replaying one that the minimap already contains does no harm.
export class MetaSync {
  private readonly options: Options;
  private meta: PageMeta | null = null;
  private buffer: MetaEvent[] = [];
  private live = false;
  private unsubscribe: (() => void) | null = null;
  private disposed = false;
  private generation = 0;

  constructor(options: Options) {
    this.options = options;
  }

  start(): void {
    if (this.disposed) {
      return;
    }
    const generation = ++this.generation;
    this.live = false;
    this.buffer = [];
    if (this.options.link.connected()) {
      this.unsubscribe = this.options.link.subscribe(
        metaTopic(this.options.pageId),
        (body) => this.onEvent(body as MetaEvent),
        () => void this.load(generation),
      );
    } else {
      void this.load(generation);
    }
  }

  dispose(): void {
    this.disposed = true;
    this.unsubscribe?.();
  }

  private async load(generation: number): Promise<void> {
    let body: MinimapBody;
    try {
      body = await this.options.fetchMinimap(this.options.pageId);
    } catch (error) {
      if (!this.disposed && error instanceof ApiError && error.code === 'PAGE_ENDED') {
        this.options.onPageEnded();
      }
      return;
    }
    if (this.disposed || generation !== this.generation) {
      return;
    }
    this.meta = { cells: decodeBitmap(body.bitmap, body.cols * body.rows), occupancyPermille: body.occupancyPermille };
    const buffered = this.buffer;
    this.buffer = [];
    this.live = true;
    buffered.forEach((e) => this.apply(e));
    this.options.onMeta(this.meta);
  }

  private onEvent(event: MetaEvent): void {
    if (this.disposed) {
      return;
    }
    if (isType(event, 'page:turned')) {
      this.options.onPageTurned();
      return;
    }
    if (isType(event, 'page:status')) {
      this.options.onStatus?.(event);
    }
    if (!this.live) {
      this.buffer.push(event);
      return;
    }
    this.apply(event);
    if (this.meta) {
      this.options.onMeta(this.meta);
    }
  }

  private apply(event: MetaEvent): void {
    if (!this.meta) {
      return;
    }
    if (isType(event, 'minimap:diff')) {
      const cells = this.meta.cells.slice();
      event.set.forEach((i) => i >= 0 && i < cells.length && (cells[i] = 1));
      event.clear.forEach((i) => i >= 0 && i < cells.length && (cells[i] = 0));
      this.meta = { cells, occupancyPermille: event.occupancyPermille };
    } else if (isType(event, 'page:status')) {
      this.meta = { ...this.meta, occupancyPermille: event.occupancyPermille };
    }
  }
}

function isType<T extends MetaEvent['type']>(event: MetaEvent, type: T): event is Extract<MetaEvent, { type: T }> {
  return event.type === type;
}
