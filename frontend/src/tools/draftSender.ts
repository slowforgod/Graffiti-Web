import type { Rect } from '../canvas/viewport';
import type { DraftStroke } from './draft';

type Pending =
  | { op: 'points'; strokeIndex: number }
  | { op: 'erase'; strokeIndex: number }
  | { op: 'text'; bbox: Rect };

interface Options {
  pageId: string;
  draftId: string;
  intervalMs: number;
  send: (body: unknown) => void;
  // The stroke as it is now; its new points are read when the message goes out.
  stroke: (index: number) => DraftStroke | null;
}

// Relays one draft (API.md §6 /app/draft): at most one message per `intervalMs` so a drawing author
// stays under the per-second SEND limit (D-12). Points of one stroke pile up into one message; a
// later batch starts with the last point already sent so the viewer's line stays joined.
export class DraftSender {
  private readonly options: Options;
  private readonly queue: Pending[] = [];
  private readonly sent = new Map<number, number>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private closed = false;

  constructor(options: Options) {
    this.options = options;
  }

  points(strokeIndex: number): void {
    if (!this.queue.some((p) => p.op === 'points' && p.strokeIndex === strokeIndex)) {
      this.push({ op: 'points', strokeIndex });
    } else {
      this.kick();
    }
  }

  erase(strokeIndex: number): void {
    this.removeWhere((p) => p.op === 'points' && p.strokeIndex === strokeIndex);
    this.push({ op: 'erase', strokeIndex });
  }

  text(bbox: Rect): void {
    this.removeWhere((p) => p.op === 'text');
    this.push({ op: 'text', bbox });
  }

  // Drops what is still waiting and tells the viewers the draft is gone.
  cancel(): void {
    this.close();
    this.options.send(this.body({ op: 'cancel' }));
  }

  // Stops without a message: the connection is gone, and the server cancels for it.
  close(): void {
    this.closed = true;
    this.queue.length = 0;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private push(p: Pending): void {
    if (this.closed) {
      return;
    }
    this.queue.push(p);
    this.kick();
  }

  // Sends now when the last message is at least one interval old, otherwise on the next tick.
  private kick(): void {
    if (this.timer === null && !this.closed) {
      this.tick();
    }
  }

  private tick(): void {
    this.timer = null;
    while (this.queue.length > 0) {
      const next = this.queue.shift()!;
      const body = this.message(next);
      if (body) {
        this.options.send(body);
        this.timer = setTimeout(() => this.tick(), this.options.intervalMs);
        return;
      }
    }
  }

  private message(p: Pending): unknown {
    if (p.op === 'erase') {
      return this.body({ op: 'erase', strokeIndex: p.strokeIndex });
    }
    if (p.op === 'text') {
      return this.body({ op: 'text', bbox: p.bbox });
    }
    const stroke = this.options.stroke(p.strokeIndex);
    const sent = this.sent.get(p.strokeIndex) ?? 0;
    if (!stroke || stroke.p.length <= sent) {
      return null;
    }
    this.sent.set(p.strokeIndex, stroke.p.length);
    return this.body({
      op: 'points',
      strokeIndex: p.strokeIndex,
      c: stroke.c,
      w: stroke.w,
      points: stroke.p.slice(Math.max(0, sent - 2)),
    });
  }

  private body(fields: Record<string, unknown>): unknown {
    return { pageId: this.options.pageId, draftId: this.options.draftId, ...fields };
  }

  private removeWhere(test: (p: Pending) => boolean): void {
    for (let i = this.queue.length - 1; i >= 0; i--) {
      if (test(this.queue[i]!)) {
        this.queue.splice(i, 1);
      }
    }
  }
}
