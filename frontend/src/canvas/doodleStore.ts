import type { ClientConfig } from '../shared/pageConfig';
import { chunksOfRect } from './chunks';
import type { Doodle } from './doodles';

type Grid = Pick<ClientConfig, 'width' | 'height' | 'chunkSize'>;

// Doodles of the loaded chunks. A doodle stays while any chunk its bbox touches is loaded.
export class DoodleStore {
  private readonly byId = new Map<string, Doodle>();
  private readonly loaded = new Set<number>();
  private readonly config: Grid;

  constructor(config: Grid) {
    this.config = config;
  }

  isLoaded(chunk: number): boolean {
    return this.loaded.has(chunk);
  }

  loadedChunks(): number[] {
    return [...this.loaded];
  }

  // Marks the chunk loaded and keeps the given doodles that touch it.
  loadChunk(chunk: number, doodles: Doodle[]): void {
    this.loaded.add(chunk);
    for (const doodle of doodles) {
      if (this.chunksOf(doodle).includes(chunk)) {
        this.byId.set(doodle.id, doodle);
      }
    }
  }

  dropChunks(chunks: Iterable<number>): void {
    for (const chunk of chunks) {
      this.loaded.delete(chunk);
    }
    for (const [id, doodle] of this.byId) {
      if (!this.chunksOf(doodle).some((c) => this.loaded.has(c))) {
        this.byId.delete(id);
      }
    }
  }

  // Adds or replaces a doodle when it touches a loaded chunk.
  put(doodle: Doodle): void {
    if (this.chunksOf(doodle).some((c) => this.loaded.has(c))) {
      this.byId.set(doodle.id, doodle);
    }
  }

  remove(id: string): void {
    this.byId.delete(id);
  }

  has(id: string): boolean {
    return this.byId.has(id);
  }

  // In publish order: ids grow with time, so the newest doodle is drawn on top.
  list(): Doodle[] {
    return [...this.byId.values()].sort((a, b) => compareIds(a.id, b.id));
  }

  chunksOf(doodle: Doodle): number[] {
    return chunksOfRect(doodle.bbox, this.config);
  }
}

// Ids are decimal strings of 64-bit integers (API.md): shorter is smaller, then compare as text.
export function compareIds(a: string, b: string): number {
  return a.length - b.length || (a < b ? -1 : a > b ? 1 : 0);
}
