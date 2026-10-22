import { describe, expect, it } from 'vitest';
import { CursorBoard } from './cursors';

const at = (sid: string, chunk: number) => ({ sid, nickname: sid, color: '#000', x: 0, y: 0, chunk });

describe('CursorBoard', () => {
  it('keeps one cursor per connection and ignores stale leaves', () => {
    const board = new CursorBoard();
    board.move(at('a', 1));
    board.move(at('a', 2));
    board.leave('a', 1);
    expect(board.list()).toEqual([at('a', 2)]);
    board.leave('a', 2);
    expect(board.list()).toEqual([]);
  });

  it('drops the cursors of chunks that left the view', () => {
    const board = new CursorBoard();
    board.move(at('a', 1));
    board.move(at('b', 2));
    board.dropChunks([1]);
    expect(board.list().map((c) => c.sid)).toEqual(['b']);
  });
});
