import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import type { CurrentPage } from '../shared/pageConfig';
import { fetchCurrentPage } from '../shared/pageConfig';
import Minimap from '../ui/Minimap';
import { decodeBitmap, fetchMinimap } from '../ui/minimap';
import CanvasView from './CanvasView';
import { ChunkLoader } from './chunkLoader';
import { visibleChunks } from './chunks';
import type { Doodle } from './doodles';
import { fetchChunkDoodles } from './doodles';
import { useWindowSize } from './useWindowSize';
import type { Viewport } from './viewport';
import { centerOn, visibleWorldRect } from './viewport';

// Waits for panning or zooming to settle before asking for new chunks.
const CHUNK_DEBOUNCE_MS = 100;

// Loads the current page and shows it. A PAGE_ENDED answer means the page turned: load the new one.
export default function CanvasScreen() {
  const [page, setPage] = useState<CurrentPage>();
  const [failed, setFailed] = useState(false);
  const [generation, setGeneration] = useState(0);
  const reload = useCallback(() => setGeneration((g) => g + 1), []);

  useEffect(() => {
    let active = true;
    fetchCurrentPage().then(
      (loaded) => active && setPage(loaded),
      () => active && setFailed(true),
    );
    return () => {
      active = false;
    };
  }, [generation]);

  if (failed) {
    return <p role="alert" className="canvas-error">캔버스를 불러오지 못했어요. 새로고침해 주세요.</p>;
  }
  if (!page) {
    return null;
  }
  return <PageCanvas key={page.id} page={page} onPageEnded={reload} />;
}

interface PageCanvasProps {
  page: CurrentPage;
  onPageEnded: () => void;
}

function PageCanvas({ page, onPageEnded }: PageCanvasProps) {
  const { config } = page;
  const size = useWindowSize();
  const [viewport, setViewport] = useState<Viewport>(() =>
    centerOn({ x: config.width / 2, y: config.height / 2 }, 1, size, config),
  );
  const [doodles, setDoodles] = useState<Doodle[]>([]);
  const [cells, setCells] = useState<Uint8Array | null>(null);
  const loader = useRef<ChunkLoader | null>(null);

  useEffect(() => {
    const created = new ChunkLoader({
      pageId: page.id,
      config,
      fetchChunks: fetchChunkDoodles,
      onChange: setDoodles,
      onPageEnded,
    });
    loader.current = created;
    return () => {
      created.dispose();
      loader.current = null;
    };
  }, [page.id, config, onPageEnded]);

  useEffect(() => {
    const timer = setTimeout(
      () => loader.current?.setVisible(visibleChunks(visibleWorldRect(viewport, size), config)),
      CHUNK_DEBOUNCE_MS,
    );
    return () => clearTimeout(timer);
  }, [viewport, size, config]);

  useEffect(() => {
    let active = true;
    fetchMinimap(page.id).then(
      (body) => active && setCells(decodeBitmap(body.bitmap, body.cols * body.rows)),
      (error: unknown) => {
        if (active && error instanceof ApiError && error.code === 'PAGE_ENDED') {
          onPageEnded();
        }
      },
    );
    return () => {
      active = false;
    };
  }, [page.id, onPageEnded]);

  return (
    <div className="canvas-screen">
      <CanvasView config={config} size={size} viewport={viewport} doodles={doodles} onViewportChange={setViewport} />
      <Minimap config={config} cells={cells} view={visibleWorldRect(viewport, size)} />
    </div>
  );
}
