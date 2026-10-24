import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../auth/me';
import { FakeLink } from '../realtime/fakeLink';
import { chunkTopic, metaTopic } from '../realtime/link';
import type { RemoteCursor } from './cursors';
import type { Doodle } from './doodles';
import type { RemoteDraft } from './remoteDrafts';
import CanvasScreen from './CanvasScreen';
import { TEST_CONFIG } from './testConfig';

const links = vi.hoisted(() => ({ current: null as null | FakeLink }));

vi.mock('../realtime/connection', async () => {
  const { FakeLink: Link } = await import('../realtime/fakeLink');
  return {
    createConnection: () => {
      const link = new Link();
      links.current = link;
      return Object.assign(link, { activate: vi.fn(), deactivate: vi.fn() });
    },
  };
});

// Konva needs a real canvas; the view is replaced by a list of the doodles it would draw.
vi.mock('./CanvasView', () => ({
  default: ({
    doodles,
    cursors,
    onPointerMove,
    otherDrafts,
    onDrawStart,
    onDrawMove,
    onDrawEnd,
  }: {
    doodles: Doodle[];
    cursors: RemoteCursor[];
    onPointerMove?: (p: { x: number; y: number }) => void;
    otherDrafts: RemoteDraft[];
    onDrawStart?: (p: { x: number; y: number }, scale: number) => void;
    onDrawMove?: (p: { x: number; y: number }, scale: number) => void;
    onDrawEnd?: () => void;
  }) => (
    <>
      <ul aria-label="drafts">
        {otherDrafts.map((d) => (
          <li key={d.draftId}>{d.draftId}</li>
        ))}
      </ul>
      <button type="button" onClick={() => onDrawStart?.({ x: 3800, y: 2100 }, 1)}>
        down
      </button>
      <button type="button" onClick={() => onDrawMove?.({ x: 3810, y: 2105 }, 1)}>
        drag
      </button>
      <button type="button" onClick={() => onDrawEnd?.()}>
        up
      </button>
      <ul aria-label="doodles">
        {doodles.map((d) => (
          <li key={d.id}>{d.id}</li>
        ))}
      </ul>
      <ul aria-label="cursors">
        {cursors.map((c) => (
          <li key={c.sid}>{c.nickname}</li>
        ))}
      </ul>
      {onPointerMove && (
        <button type="button" onClick={() => onPointerMove({ x: 3800, y: 2100 })}>
          move
        </button>
      )}
    </>
  ),
}));

function page(id: string, config = TEST_CONFIG) {
  return {
    id,
    seq: Number(id),
    status: 'ACTIVE',
    occupancyPermille: 0,
    countdownStartedAt: null,
    scheduledEndAt: '2026-10-28T00:00:00Z',
    serverTime: '2026-10-21T00:00:00Z',
    config,
  };
}

const minimap = { cols: 160, rows: 90, bitmap: btoa('\0'.repeat(1800)), occupancyPermille: 0 };

function reply(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

const pageEnded = { error: { code: 'PAGE_ENDED', message: '' } };

describe('CanvasScreen', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    fetchMock.mockReset();
  });

  it('loads the current page and the doodles of the visible chunks', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/pages/current') return reply(page('3'));
      if (url.endsWith('/minimap')) return reply(minimap);
      if (url.startsWith('/api/pages/3/doodles')) {
        return reply({ pageId: '3', doodles: [{ id: '11', kind: 'DRAWING', bbox: { x: 3800, y: 2100, w: 10, h: 10 }, payload: { v: 1, strokes: [] } }] });
      }
      return reply({}, 404);
    });

    render(<CanvasScreen />);

    expect(await screen.findByText('11')).toBeInTheDocument();
    const chunkCalls = fetchMock.mock.calls.map((c) => String(c[0])).filter((u) => u.includes('/doodles'));
    expect(chunkCalls).toHaveLength(1);
    const chunks = decodeURIComponent(chunkCalls[0]!.split('chunks=')[1]!).split(',');
    expect(chunks.length).toBeGreaterThan(0);
    expect(chunks.length).toBeLessThanOrEqual(TEST_CONFIG.maxChunks);
    expect(screen.getByRole('img', { name: '미니맵' })).toBeInTheDocument();
  });

  it('loads the next page when the shown page has ended', async () => {
    let current = '3';
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/pages/current') return reply(page(current));
      if (url.endsWith('/minimap')) return reply(minimap);
      if (url.startsWith('/api/pages/3/')) {
        current = '4';
        return reply(pageEnded, 409);
      }
      if (url.startsWith('/api/pages/4/doodles')) {
        return reply({ pageId: '4', doodles: [{ id: '40', kind: 'DRAWING', bbox: { x: 3800, y: 2100, w: 10, h: 10 }, payload: { v: 1, strokes: [] } }] });
      }
      return reply({}, 404);
    });

    render(<CanvasScreen />);

    expect(await screen.findByText('40')).toBeInTheDocument();
  });

  it('loads the next page on page:turned', async () => {
    let current = '3';
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/pages/current') return reply(page(current));
      if (url.endsWith('/minimap')) return reply(minimap);
      if (url.startsWith(`/api/pages/${current}/doodles`)) {
        return reply({ pageId: current, doodles: [{ id: `${current}0`, kind: 'DRAWING', bbox: { x: 3800, y: 2100, w: 10, h: 10 }, payload: { v: 1, strokes: [] } }] });
      }
      return reply({}, 404);
    });

    render(<CanvasScreen />);
    expect(await screen.findByText('30')).toBeInTheDocument();

    current = '4';
    act(() => {
      links.current!.connect();
      links.current!.readyAll();
    });
    act(() => links.current!.deliver(metaTopic('3'), { type: 'page:turned', newPageId: '4' }));
    // The new page subscribes to its chunks (the link is connected now) and queries after the receipts.
    await waitFor(() => {
      act(() => links.current!.readyAll());
      expect(screen.getByText('40')).toBeInTheDocument();
    });
  });

  it('shows other cursors, hides its own and sends its cursor when it may draw', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/pages/current') return reply(page('3'));
      if (url.endsWith('/minimap')) return reply(minimap);
      if (url.startsWith('/api/pages/3/doodles')) return reply({ pageId: '3', doodles: [] });
      return reply({}, 404);
    });
    const me: Me = {
      authenticated: true,
      id: '1',
      nickname: '나',
      cursorColor: '#000000',
      role: 'USER',
      consentRequired: false,
      banned: false,
      cooldownEndsAt: null,
    };

    render(<CanvasScreen me={me} />);
    const move = await screen.findByRole('button', { name: 'move' });
    act(() => links.current!.connect());
    await act(async () => {
      await new Promise((r) => setTimeout(r, 150));
    });
    const chunk = links.current!.active().find((s) => s.destination.includes('.chunk.'))!.destination;
    act(() => {
      links.current!.deliver(chunk, { type: 'cursor', sid: 's-1', nickname: '나', color: '#000', x: 1, y: 1 });
      links.current!.deliver(chunk, { type: 'cursor', sid: 's-2', nickname: '너', color: '#000', x: 1, y: 1 });
    });
    expect(screen.getByRole('list', { name: 'cursors' })).toHaveTextContent('너');
    expect(screen.getByRole('list', { name: 'cursors' })).not.toHaveTextContent('나');

    act(() => move.click());
    expect(links.current!.sent).toEqual([{ destination: '/app/cursor', body: { pageId: '3', x: 3800, y: 2100 } }]);
    expect(chunk).toMatch(/^\/topic\/page\.3\.chunk\.\d+$/);
    expect(chunkTopic('3', 0)).toBe('/topic/page.3.chunk.0');
  });

  describe('drafts', () => {
    const me: Me = {
      authenticated: true,
      id: '1',
      nickname: '나',
      cursorColor: '#000000',
      role: 'USER',
      consentRequired: false,
      banned: false,
      cooldownEndsAt: null,
    };

    type Post = (body: Record<string, unknown>) => Response | Promise<Response>;

    async function open(config = TEST_CONFIG, who: Me = me, post?: Post) {
      fetchMock.mockImplementation(async (input, init) => {
        const url = String(input);
        if (url === '/api/pages/3/doodles' && init?.method === 'POST' && post) {
          return post(JSON.parse(String(init.body)) as Record<string, unknown>);
        }
        if (url === '/api/pages/current') return reply(page('3', config));
        if (url.endsWith('/minimap')) return reply(minimap);
        if (url.startsWith('/api/pages/3/doodles')) return reply({ pageId: '3', doodles: [] });
        return reply({}, 404);
      });
      render(<CanvasScreen me={who} />);
      await screen.findByRole('button', { name: 'down' });
      act(() => links.current!.connect());
      await act(async () => {
        await new Promise((r) => setTimeout(r, 150));
      });
      act(() => screen.getByRole('button', { name: '펜' }).click());
      const link = links.current!;
      return { link, drafts: () => link.sent.filter((m) => m.destination === '/app/draft').map((m) => m.body as Record<string, unknown>) };
    }

    function draw() {
      act(() => screen.getByRole('button', { name: 'down' }).click());
      act(() => screen.getByRole('button', { name: 'drag' }).click());
      act(() => screen.getByRole('button', { name: 'up' }).click());
    }

    it('relays a pen stroke, rests the cursor and cancels with ✗ (AC-9, AC-11)', async () => {
      const { link, drafts } = await open();
      draw();

      expect(drafts()[0]).toMatchObject({ pageId: '3', op: 'points', strokeIndex: 0, c: 0, points: [3800, 2100] });
      const draftId = drafts()[0]!.draftId;
      // The cursor rests while a draft is open: the view gets no pointer handler for it.
      expect(screen.queryByRole('button', { name: 'move' })).not.toBeInTheDocument();
      expect(link.sent.some((m) => m.destination === '/app/cursor')).toBe(false);

      act(() => screen.getByRole('button', { name: '취소' }).click());
      expect(drafts().at(-1)).toEqual({ pageId: '3', draftId, op: 'cancel' });
      expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument();
    });

    const published = {
      doodle: { id: '77', kind: 'DRAWING', bbox: { x: 3796, y: 2096, w: 18, h: 13 }, payload: { v: 1, strokes: [] } },
      cooldownEndsAt: '2026-10-21T00:30:00Z',
    };

    it('posts the draft with ✓, shows it at once and starts the cooldown (AC-11, AC-12)', async () => {
      let sentBody: Record<string, unknown> | undefined;
      const { link, drafts } = await open(TEST_CONFIG, me, (body) => {
        sentBody = body;
        return reply(published, 201);
      });
      // The visible chunks are loaded once their subscriptions are confirmed (INV-09).
      await act(async () => {
        link.readyAll();
        await new Promise((r) => setTimeout(r, 0));
      });
      draw();
      const draftId = drafts()[0]!.draftId;

      await act(async () => screen.getByRole('button', { name: '게시' }).click());

      expect(sentBody).toEqual({
        kind: 'DRAWING',
        payload: { v: 1, strokes: [{ c: 0, w: TEST_CONFIG.strokeWidths[1], p: [3800, 2100, 3810, 2105] }] },
      });
      expect(screen.getByRole('list', { name: 'doodles' })).toHaveTextContent('77');
      expect(drafts().at(-1)).toEqual({ pageId: '3', draftId, op: 'cancel' });
      expect(screen.queryByRole('button', { name: '게시' })).not.toBeInTheDocument();

      // The next draft waits for the cooldown, judged by the server clock of the page load.
      draw();
      expect(screen.getByRole('button', { name: '게시' })).toBeDisabled();
      expect(screen.getByText('30분 뒤에 게시할 수 있어요')).toBeInTheDocument();
    });

    it('keeps the draft and explains when the post is refused (AC-13)', async () => {
      const { drafts } = await open(TEST_CONFIG, me, () =>
        reply({ error: { code: 'COOLDOWN', message: '다음 게시까지 12분 남았어요.', details: { retryAfterSec: 700 } } }, 429),
      );
      draw();

      await act(async () => screen.getByRole('button', { name: '게시' }).click());

      expect(screen.getByRole('status')).toHaveTextContent('다음 게시까지 12분 남았어요.');
      expect(screen.getByRole('button', { name: '취소' })).toBeEnabled();
      expect(drafts().some((d) => d.op === 'cancel')).toBe(false);
      expect(screen.getByRole('button', { name: '게시' })).toBeDisabled();
      expect(screen.getByText('12분 뒤에 게시할 수 있어요')).toBeInTheDocument();
    });

    it('clears the draft and says it waits for review when the post is held', async () => {
      const { drafts } = await open(TEST_CONFIG, me, () =>
        reply({ held: true, cooldownEndsAt: '2026-10-21T00:30:00Z' }, 202),
      );
      draw();
      const draftId = drafts()[0]!.draftId;

      await act(async () => screen.getByRole('button', { name: '게시' }).click());

      expect(screen.getByRole('status')).toHaveTextContent('검토 후 게시돼요.');
      expect(drafts().at(-1)).toEqual({ pageId: '3', draftId, op: 'cancel' });
      expect(screen.getByRole('list', { name: 'doodles' })).toBeEmptyDOMElement();
      draw();
      expect(screen.getByText('30분 뒤에 게시할 수 있어요')).toBeInTheDocument();
    });

    it('keeps the draft but uses up the post when moderation rejects it', async () => {
      const { drafts } = await open(TEST_CONFIG, me, () =>
        reply(
          {
            error: {
              code: 'MODERATION_REJECTED',
              message: '부적절한 내용이 감지되어 게시할 수 없어요.',
              details: { category: 'PROFANITY', cooldownEndsAt: '2026-10-21T00:30:00Z' },
            },
          },
          422,
        ),
      );
      draw();

      await act(async () => screen.getByRole('button', { name: '게시' }).click());

      expect(screen.getByRole('status')).toHaveTextContent('부적절한 내용이 감지되어 게시할 수 없어요.');
      expect(drafts().some((d) => d.op === 'cancel')).toBe(false);
      expect(screen.getByRole('button', { name: '게시' })).toBeDisabled();
      expect(screen.getByText('30분 뒤에 게시할 수 있어요')).toBeInTheDocument();
    });

    it('disables ✓ and ✗ while the post is on its way', async () => {
      let answer: (r: Response) => void = () => {};
      await open(TEST_CONFIG, me, () => new Promise<Response>((resolve) => (answer = resolve)));
      draw();

      act(() => screen.getByRole('button', { name: '게시' }).click());

      expect(screen.getByRole('button', { name: '게시' })).toBeDisabled();
      expect(screen.getByRole('button', { name: '취소' })).toBeDisabled();
      await act(async () => answer(reply({ error: { code: 'INTERNAL', message: '일시적인 오류가 발생했어요.' } }, 500)));
      expect(screen.getByRole('button', { name: '게시' })).toBeEnabled();
      expect(screen.getByRole('status')).toHaveTextContent('일시적인 오류가 발생했어요.');
    });

    it('waits out the cooldown it learned from /api/me', async () => {
      await open(TEST_CONFIG, { ...me, cooldownEndsAt: '2026-10-21T00:05:00Z' } as Me);
      draw();
      expect(screen.getByRole('button', { name: '게시' })).toBeDisabled();
      expect(screen.getByText('5분 뒤에 게시할 수 있어요')).toBeInTheDocument();
    });

    it('loads the new page when the post finds the page ended (AC-13)', async () => {
      await open(TEST_CONFIG, me, () => reply(pageEnded, 409));
      draw();
      const loads = () => fetchMock.mock.calls.filter((c) => String(c[0]) === '/api/pages/current').length;
      const before = loads();

      await act(async () => screen.getByRole('button', { name: '게시' }).click());

      await waitFor(() => expect(loads()).toBe(before + 1));
    });

    it("hides the echo of its own draft but shows other people's (AC-15)", async () => {
      const { link, drafts } = await open();
      draw();
      const chunk = link.active().find((s) => s.destination.includes('.chunk.'))!.destination;
      const own = drafts()[0]!.draftId as string;
      act(() => {
        link.deliver(chunk, { type: 'draft:update', sid: 's-1', draftId: own, op: 'points', strokeIndex: 0, c: 0, w: 4, points: [1, 1] });
        link.deliver(chunk, { type: 'draft:update', sid: 's-2', draftId: 'theirs', op: 'text', bbox: { x: 1, y: 1, w: 9, h: 9 } });
      });
      expect(screen.getByRole('list', { name: 'drafts' })).toHaveTextContent('theirs');
      expect(screen.getByRole('list', { name: 'drafts' })).not.toHaveTextContent(own);
    });

    it('drops the draft when the connection drops (D-11)', async () => {
      const { link } = await open();
      draw();
      act(() => link.disconnect());
      expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent('연결이 끊겨 그리던 초안이 사라졌어요.');
      expect(screen.getByRole('button', { name: '펜' })).toBeDisabled();
    });

    it('cancels a draft left alone for the idle timeout (D-11)', async () => {
      const { drafts } = await open({ ...TEST_CONFIG, draftIdleTimeoutSec: 0.2 });
      draw();
      await act(async () => {
        await new Promise((r) => setTimeout(r, 300));
      });
      expect(drafts().at(-1)).toMatchObject({ op: 'cancel' });
      expect(screen.getByRole('status')).toHaveTextContent('10분 동안 움직임이 없어 초안을 지웠어요.');
    });
  });

  it('explains when the page cannot be loaded', async () => {
    fetchMock.mockResolvedValue(reply({ error: { code: 'INTERNAL', message: '' } }, 500));
    await act(async () => {
      render(<CanvasScreen />);
    });
    expect(await screen.findByRole('alert')).toHaveTextContent('캔버스를 불러오지 못했어요. 새로고침해 주세요.');
  });
});
