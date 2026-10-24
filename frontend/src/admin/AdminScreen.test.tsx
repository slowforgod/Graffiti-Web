import { act, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../auth/me';
import { TEST_CONFIG } from '../canvas/testConfig';
import AdminScreen from './AdminScreen';
import { describeChecks } from './adminApi';

// Konva needs a real canvas; the preview only says which doodle it would draw.
vi.mock('./DoodlePreview', () => ({
  default: ({ doodle }: { doodle: { id: string } }) => <span>preview {doodle.id}</span>,
}));

const admin = {
  authenticated: true,
  id: '1',
  nickname: '익명의 펭귄 #1',
  cursorColor: '#000000',
  role: 'ADMIN',
  consentRequired: false,
  banned: false,
  cooldownEndsAt: null,
} as Me;

function item(id: string, status: string, pageEnded = false) {
  return {
    id,
    pageId: '3',
    pageEnded,
    kind: 'DRAWING',
    bbox: { x: 0, y: 0, w: 10, h: 10 },
    payload: { v: 1, strokes: [] },
    status,
    moderationState: status,
    moderationDetail: { checks: [{ check: 'word-filter', outcome: 'SKIPPED' }], heldAsUnchecked: true },
    submittedAt: '2026-10-21T00:00:00Z',
    author: { id: '9', nickname: '익명의 고양이 #2', email: 'cat@khu.ac.kr' },
  };
}

function reply(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function route(handlers: Record<string, (init?: RequestInit) => Response>) {
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input);
    const key = Object.keys(handlers).find((k) => url.startsWith(k));
    if (!key) {
      throw new Error(`unexpected ${url}`);
    }
    return handlers[key]!(init);
  });
}

describe('AdminScreen', () => {
  it('refuses people who are not admins', async () => {
    await act(async () => {
      render(<AdminScreen me={{ ...admin, role: 'USER' } as Me} />);
    });
    expect(screen.getByRole('alert')).toHaveTextContent('관리자만 볼 수 있어요.');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('lists held doodles and publishes or rejects them', async () => {
    route({
      '/api/admin/doodles?moderation=HELD': () => reply({ items: [item('5', 'HELD'), item('6', 'HELD')], page: 0, size: 20, total: 2 }),
      '/api/pages/current': () => reply({ config: TEST_CONFIG }),
      '/api/admin/doodles/5/restore': () => reply({ doodle: item('5', 'PUBLISHED') }),
      '/api/admin/doodles/6/reject': () => reply({ id: '6', status: 'REJECTED' }),
    });
    await act(async () => {
      render(<AdminScreen me={admin} />);
    });

    const list = await screen.findByRole('list', { name: '낙서 목록' });
    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(2));
    expect(within(list).getAllByText(/cat@khu.ac.kr/)).toHaveLength(2);
    expect(screen.getByText('2개')).toBeInTheDocument();

    const [first, second] = within(list).getAllByRole('listitem');
    await act(async () => within(first!).getByRole('button', { name: '게시' }).click());
    await act(async () => within(second!).getByRole('button', { name: '거부' }).click());

    expect(within(list).queryAllByRole('listitem')).toHaveLength(0);
    const posts = fetchMock.mock.calls.filter((c) => c[1]?.method === 'POST').map((c) => String(c[0]));
    expect(posts).toEqual(['/api/admin/doodles/5/restore', '/api/admin/doodles/6/reject']);
  });

  it('keeps the doodle and explains when its page has ended', async () => {
    route({
      '/api/admin/doodles?moderation=HELD': () => reply({ items: [item('5', 'HELD')], page: 0, size: 20, total: 1 }),
      '/api/pages/current': () => reply({ config: TEST_CONFIG }),
      '/api/admin/doodles/5/restore': () =>
        reply({ error: { code: 'PAGE_ENDED', message: '이 페이지는 이미 넘어갔어요.' } }, 409),
    });
    await act(async () => {
      render(<AdminScreen me={admin} />);
    });
    const list = await screen.findByRole('list', { name: '낙서 목록' });
    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(1));

    await act(async () => screen.getByRole('button', { name: '게시' }).click());

    expect(screen.getByRole('alert')).toHaveTextContent('이미 끝난 페이지의 낙서라 게시할 수 없어요.');
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
  });

  it('switches to the rejected list', async () => {
    route({
      '/api/admin/doodles?moderation=HELD': () => reply({ items: [], page: 0, size: 20, total: 0 }),
      '/api/admin/doodles?moderation=REJECTED': () => reply({ items: [item('8', 'REJECTED')], page: 0, size: 20, total: 1 }),
      '/api/pages/current': () => reply({ config: TEST_CONFIG }),
    });
    await act(async () => {
      render(<AdminScreen me={admin} />);
    });

    await act(async () => screen.getByRole('button', { name: '거부' }).click());

    expect(await screen.findByRole('button', { name: '복구해서 게시' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '거부', pressed: true })).toBeInTheDocument();
  });
});

describe('describeChecks', () => {
  it('names each check and outcome without any user text', () => {
    expect(
      describeChecks({
        checks: [
          { check: 'word-filter', outcome: 'PASS' },
          { check: 'chatkhu', outcome: 'ERROR', note: 'TIMEOUT' },
        ],
        ml: { check: 'ml', outcome: 'BLOCK', category: 'SEXUAL', mode: 'SHADOW' },
      }),
    ).toBe('word-filter 통과 · chatkhu 실패(TIMEOUT) · ml(SHADOW) 차단(SEXUAL)');
    expect(describeChecks({ checks: [], heldAsUnchecked: true })).toBe('실제 검사 없음 → 보류');
    expect(describeChecks(null)).toBe('검사 기록 없음');
  });
});
