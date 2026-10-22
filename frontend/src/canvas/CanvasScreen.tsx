import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Me } from '../auth/me';
import type { RealtimeLink } from '../realtime/link';
import type { Connection } from '../realtime/connection';
import { createConnection } from '../realtime/connection';
import { MetaSync } from '../realtime/metaSync';
import { throttle } from '../realtime/throttle';
import type { CurrentPage } from '../shared/pageConfig';
import { fetchCurrentPage } from '../shared/pageConfig';
import Minimap from '../ui/Minimap';
import { fetchMinimap } from '../ui/minimap';
import CanvasView from './CanvasView';
import { ChunkSync } from './chunkSync';
import { visibleChunks } from './chunks';
import type { RemoteCursor } from './cursors';
import type { Doodle } from './doodles';
import { fetchChunkDoodles } from './doodles';
import { useWindowSize } from './useWindowSize';
import type { Point, Viewport } from './viewport';
import { centerOn, visibleWorldRect } from './viewport';

// Waits for panning or zooming to settle before asking for new chunks.
const CHUNK_DEBOUNCE_MS = 100;

interface Props {
  me?: Me;
}

// Loads the current page and shows it. A PAGE_ENDED answer or page:turned means the page turned:
// load the new one. One STOMP connection serves every page the screen shows.
export default function CanvasScreen({ me }: Props) {
  const [page, setPage] = useState<CurrentPage>();
  const [failed, setFailed] = useState(false);
  const [generation, setGeneration] = useState(0);
  const reload = useCallback(() => setGeneration((g) => g + 1), []);
  const [connection] = useState<Connection>(() => createConnection());
  const heartbeatMs = page?.config.heartbeatMs;

  useEffect(() => {
    if (heartbeatMs === undefined) {
      return;
    }
    connection.activate(heartbeatMs);
    const stop = connection.onPageEnded(reload);
    return () => {
      stop();
      connection.deactivate();
    };
  }, [connection, heartbeatMs, reload]);

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
  return <PageCanvas key={page.id} page={page} link={connection} me={me} onPageEnded={reload} />;
}

interface PageCanvasProps {
  page: CurrentPage;
  link: RealtimeLink;
  me?: Me;
  onPageEnded: () => void;
}

function PageCanvas({ page, link, me, onPageEnded }: PageCanvasProps) {
  const { config } = page;
  const size = useWindowSize();
  const [viewport, setViewport] = useState<Viewport>(() =>
    centerOn({ x: config.width / 2, y: config.height / 2 }, 1, size, config),
  );
  const [doodles, setDoodles] = useState<Doodle[]>([]);
  const [cursors, setCursors] = useState<RemoteCursor[]>([]);
  const [cells, setCells] = useState<Uint8Array | null>(null);
  const sync = useRef<ChunkSync | null>(null);

  useEffect(() => {
    const chunks = new ChunkSync({
      pageId: page.id,
      config,
      link,
      fetchChunks: fetchChunkDoodles,
      onDoodles: setDoodles,
      onCursors: setCursors,
      onPageEnded,
    });
    const meta = new MetaSync({
      pageId: page.id,
      link,
      fetchMinimap,
      onMeta: (m) => setCells(m.cells),
      onPageTurned: onPageEnded,
      onPageEnded,
    });
    sync.current = chunks;
    meta.start();
    const stop = link.onConnect(() => {
      chunks.resync();
      meta.start();
    });
    return () => {
      stop();
      chunks.dispose();
      meta.dispose();
      sync.current = null;
    };
  }, [page.id, config, link, onPageEnded]);

  useEffect(() => {
    const timer = setTimeout(
      () => sync.current?.setVisible(visibleChunks(visibleWorldRect(viewport, size), config)),
      CHUNK_DEBOUNCE_MS,
    );
    return () => clearTimeout(timer);
  }, [viewport, size, config]);

  // Only people who may draw send their cursor (API.md §6); their own comes back and is hidden.
  const author = me?.authenticated && !me.consentRequired && !me.banned ? me : null;
  const sendCursor = useMemo(
    () =>
      author &&
      throttle<Point>(config.cursorIntervalMs, (p) => link.send('/app/cursor', { pageId: page.id, x: p.x, y: p.y })),
    [author, config.cursorIntervalMs, link, page.id],
  );
  useEffect(() => () => sendCursor?.cancel(), [sendCursor]);
  const others = author ? cursors.filter((c) => c.nickname !== author.nickname) : cursors;

  return (
    <div className="canvas-screen">
      <CanvasView
        config={config}
        size={size}
        viewport={viewport}
        doodles={doodles}
        cursors={others}
        onViewportChange={setViewport}
        onPointerMove={sendCursor ? (p) => sendCursor.push(p) : undefined}
      />
      <Minimap config={config} cells={cells} view={visibleWorldRect(viewport, size)} />
    </div>
  );
}
