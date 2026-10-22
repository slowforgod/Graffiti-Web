import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Doodle } from './doodles';
import CanvasScreen from './CanvasScreen';
import { TEST_CONFIG } from './testConfig';

// Konva needs a real canvas; the view is replaced by a list of the doodles it would draw.
vi.mock('./CanvasView', () => ({
  default: ({ doodles }: { doodles: Doodle[] }) => (
    <ul aria-label="doodles">
      {doodles.map((d) => (
        <li key={d.id}>{d.id}</li>
      ))}
    </ul>
  ),
}));

function page(id: string) {
  return {
    id,
    seq: Number(id),
    status: 'ACTIVE',
    occupancyPermille: 0,
    countdownStartedAt: null,
    scheduledEndAt: '2026-10-28T00:00:00Z',
    serverTime: '2026-10-21T00:00:00Z',
    config: TEST_CONFIG,
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

  it('explains when the page cannot be loaded', async () => {
    fetchMock.mockResolvedValue(reply({ error: { code: 'INTERNAL', message: '' } }, 500));
    await act(async () => {
      render(<CanvasScreen />);
    });
    expect(await screen.findByRole('alert')).toHaveTextContent('캔버스를 불러오지 못했어요. 새로고침해 주세요.');
  });
});
