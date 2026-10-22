// Other people's cursors, keyed by the per-connection sid (API.md §6). Never stored server-side (INV-06).
export interface RemoteCursor {
  sid: string;
  nickname: string;
  color: string;
  x: number;
  y: number;
  chunk: number;
}

export class CursorBoard {
  private readonly bySid = new Map<string, RemoteCursor>();

  move(cursor: RemoteCursor): void {
    this.bySid.set(cursor.sid, cursor);
  }

  // A leave from a chunk the cursor already moved out of is stale and ignored.
  leave(sid: string, chunk: number): void {
    if (this.bySid.get(sid)?.chunk === chunk) {
      this.bySid.delete(sid);
    }
  }

  dropChunks(chunks: Iterable<number>): void {
    const dropped = new Set(chunks);
    for (const [sid, cursor] of this.bySid) {
      if (dropped.has(cursor.chunk)) {
        this.bySid.delete(sid);
      }
    }
  }

  clear(): void {
    this.bySid.clear();
  }

  list(): RemoteCursor[] {
    return [...this.bySid.values()];
  }
}
