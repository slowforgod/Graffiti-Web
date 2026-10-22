import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { MinimapBody } from '../ui/minimap';
import { FakeLink } from './fakeLink';
import { metaTopic } from './link';
import type { PageMeta } from './metaSync';
import { MetaSync } from './metaSync';

// 2 × 8 cells, all empty.
const EMPTY: MinimapBody = { cols: 8, rows: 2, bitmap: btoa('\0\0'), occupancyPermille: 0 };
const flush = () => new Promise((r) => setTimeout(r, 0));

function setup(fetchMinimap: (pageId: string) => Promise<MinimapBody> = async () => EMPTY) {
  const link = new FakeLink();
  link.isConnected = true;
  let meta: PageMeta | null = null;
  const onPageTurned = vi.fn();
  const onPageEnded = vi.fn();
  const sync = new MetaSync({
    pageId: '7',
    link,
    fetchMinimap: vi.fn(fetchMinimap),
    onMeta: (m) => (meta = m),
    onPageTurned,
    onPageEnded,
  });
  return { link, sync, onPageTurned, onPageEnded, meta: () => meta };
}

describe('MetaSync', () => {
  it('fetches the minimap after the receipt and replays the diffs that came first', async () => {
    const { link, sync, meta } = setup();
    sync.start();
    link.deliver(metaTopic('7'), { type: 'minimap:diff', set: [3], clear: [], occupancyPermille: 62 });
    expect(meta()).toBeNull();

    link.readyAll();
    await flush();
    expect([...meta()!.cells]).toEqual([0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    expect(meta()!.occupancyPermille).toBe(62);

    link.deliver(metaTopic('7'), { type: 'minimap:diff', set: [15], clear: [3], occupancyPermille: 62 });
    expect(meta()!.cells[3]).toBe(0);
    expect(meta()!.cells[15]).toBe(1);
    link.deliver(metaTopic('7'), { type: 'page:status', status: 'COUNTDOWN', occupancyPermille: 900, countdownStartedAt: null, scheduledEndAt: '' });
    expect(meta()!.occupancyPermille).toBe(900);
  });

  it('reports page:turned', () => {
    const { link, sync, onPageTurned } = setup();
    sync.start();
    link.deliver(metaTopic('7'), { type: 'page:turned', newPageId: '8' });
    expect(onPageTurned).toHaveBeenCalled();
  });

  it('reports PAGE_ENDED from the minimap query', async () => {
    const { link, sync, onPageEnded } = setup(async () => {
      throw new ApiError(409, 'PAGE_ENDED', '');
    });
    sync.start();
    link.readyAll();
    await flush();
    expect(onPageEnded).toHaveBeenCalled();
  });

  it('fetches at once while disconnected', async () => {
    const { link, sync, meta } = setup();
    link.isConnected = false;
    sync.start();
    await flush();
    expect(link.subscriptions).toEqual([]);
    expect(meta()).not.toBeNull();
  });
});
