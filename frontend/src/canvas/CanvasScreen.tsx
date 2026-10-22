import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import type { Me } from '../auth/me';
import { LOGIN_URL } from '../auth/me';
import type { RealtimeLink } from '../realtime/link';
import type { Connection } from '../realtime/connection';
import { createConnection } from '../realtime/connection';
import { MetaSync } from '../realtime/metaSync';
import { throttle } from '../realtime/throttle';
import type { CurrentPage } from '../shared/pageConfig';
import { fetchCurrentPage } from '../shared/pageConfig';
import { clockOffset, cooldownLeft, endsAfter, minutesLeft } from '../tools/cooldown';
import { draftBounds } from '../tools/draft';
import { publishBody, publishDoodle } from '../tools/publish';
import type { DraftNotice } from '../tools/useDraft';
import { useDraft } from '../tools/useDraft';
import DraftActions from '../ui/DraftActions';
import Minimap from '../ui/Minimap';
import TextDraftEditor from '../ui/TextDraftEditor';
import type { DrawAccess } from '../ui/Toolbar';
import Toolbar from '../ui/Toolbar';
import { fetchMinimap } from '../ui/minimap';
import CanvasView from './CanvasView';
import { ChunkSync } from './chunkSync';
import { visibleChunks } from './chunks';
import type { RemoteCursor } from './cursors';
import type { Doodle } from './doodles';
import type { RemoteDraft } from './remoteDrafts';
import { fetchChunkDoodles } from './doodles';
import { useWindowSize } from './useWindowSize';
import type { Point, Viewport } from './viewport';
import { centerOn, visibleWorldRect } from './viewport';

// Waits for panning or zooming to settle before asking for new chunks.
const CHUNK_DEBOUNCE_MS = 100;

// How often other people's silent drafts are looked at for the idle timeout.
const DRAFT_EXPIRY_CHECK_MS = 30_000;

const NOTICES: Record<DraftNotice, string> = {
  'too-big': '낙서 하나는 가로세로 1024px 안에 그려요.',
  'text-full': '글자는 줄바꿈을 빼고 50자까지 쓸 수 있어요.',
  idle: '10분 동안 움직임이 없어 초안을 지웠어요.',
  disconnected: '연결이 끊겨 그리던 초안이 사라졌어요.',
};

// Shown when a post fails for a reason the server did not word itself (a dropped connection).
const PUBLISH_FAILED = '게시하지 못했어요. 잠시 후 다시 시도해 주세요.';

// Longest wait between redraws of the cooldown note.
const COOLDOWN_TICK_MS = 30_000;

function drawAccess(me: Me | undefined, online: boolean): DrawAccess {
  if (!me) {
    return 'loading';
  }
  if (!me.authenticated) {
    return 'anonymous';
  }
  if (me.consentRequired) {
    return 'consent';
  }
  if (me.banned) {
    return 'banned';
  }
  return online ? 'ok' : 'offline';
}

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
  const [otherDrafts, setOtherDrafts] = useState<RemoteDraft[]>([]);
  const sync = useRef<ChunkSync | null>(null);
  const tools = useDraft({ pageId: page.id, config, link });
  const [offset] = useState(() => clockOffset(page.serverTime));
  // The cooldown this screen learned from its own posts; until then the one from /api/me.
  const [postedCooldown, setPostedCooldown] = useState<string | null>();
  const [publishing, setPublishing] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const chunks = new ChunkSync({
      pageId: page.id,
      config,
      link,
      fetchChunks: fetchChunkDoodles,
      onDoodles: setDoodles,
      onCursors: setCursors,
      onDrafts: setOtherDrafts,
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
    const expiry = setInterval(
      () => chunks.expireDrafts(config.draftIdleTimeoutSec * 1000),
      Math.min(DRAFT_EXPIRY_CHECK_MS, config.draftIdleTimeoutSec * 1000),
    );
    const stop = link.onConnect(() => {
      chunks.resync();
      meta.start();
    });
    return () => {
      stop();
      clearInterval(expiry);
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
  // While a draft is open the cursor rests: the draft shows where the author is and keeps the
  // author under the SEND limit (D-12).
  const author = me?.authenticated && !me.consentRequired && !me.banned ? me : null;
  const access = drawAccess(me, tools.online);
  const own = tools.draft;
  const bounds = own ? draftBounds(own) : null;
  const tool = access === 'ok' ? tools.tool : 'move';
  const sendCursor = useMemo(
    () =>
      author &&
      throttle<Point>(config.cursorIntervalMs, (p) => link.send('/app/cursor', { pageId: page.id, x: p.x, y: p.y })),
    [author, config.cursorIntervalMs, link, page.id],
  );
  useEffect(() => () => sendCursor?.cancel(), [sendCursor]);
  const cooldownEndsAt = postedCooldown !== undefined ? postedCooldown : (author?.cooldownEndsAt ?? null);
  const waitMs = cooldownLeft(cooldownEndsAt, offset, now);

  // Redraws the note as minutes pass and enables ✓ when the cooldown ends.
  useEffect(() => {
    if (waitMs <= 0) {
      return;
    }
    const timer = setTimeout(() => setNow(Date.now()), Math.min(waitMs, COOLDOWN_TICK_MS));
    return () => clearTimeout(timer);
  }, [waitMs]);

  const publish = async () => {
    const body = own && publishBody(own);
    if (!body || publishing) {
      return;
    }
    setPublishing(true);
    setPublishError(null);
    try {
      const published = await publishDoodle(page.id, body);
      sync.current?.addPublished(published.doodle);
      setPostedCooldown(published.cooldownEndsAt);
      // Tells the viewers to drop the half-transparent draft (ARCHITECTURE.md §4.3).
      tools.cancel();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'PAGE_ENDED') {
        onPageEnded();
        return;
      }
      if (e instanceof ApiError && e.code === 'COOLDOWN' && typeof e.details.retryAfterSec === 'number') {
        setPostedCooldown(endsAfter(e.details.retryAfterSec, offset));
      }
      setPublishError(e instanceof ApiError && e.message ? e.message : PUBLISH_FAILED);
    } finally {
      setPublishing(false);
      setNow(Date.now());
    }
  };

  const canPost = own !== null && publishBody(own) !== null && waitMs <= 0;
  const publishNote = waitMs > 0 ? `${minutesLeft(waitMs)}분 뒤에 게시할 수 있어요` : undefined;
  const notice = publishError ?? (tools.notice ? NOTICES[tools.notice] : null);

  const others = author ? cursors.filter((c) => c.nickname !== author.nickname) : cursors;
  // The author's own draft comes back from the chunk topics too.
  const drafts = own ? otherDrafts.filter((d) => d.draftId !== own.id) : otherDrafts;

  return (
    <div className="canvas-screen">
      <CanvasView
        config={config}
        size={size}
        viewport={viewport}
        doodles={doodles}
        cursors={others}
        onViewportChange={setViewport}
        onPointerMove={sendCursor && !own ? (p) => sendCursor.push(p) : undefined}
        tool={publishing ? 'move' : tool}
        ownDraft={own}
        otherDrafts={drafts}
        onDrawStart={tools.pointerDown}
        onDrawMove={tools.pointerMove}
        onDrawEnd={tools.pointerUp}
      />
      {own?.kind === 'TEXT' && (
        <TextDraftEditor
          draft={own}
          color={config.palette[own.c] ?? config.palette[0]!}
          viewport={viewport}
          onChange={tools.editText}
        />
      )}
      {own && bounds && (
        <DraftActions
          bounds={bounds}
          viewport={viewport}
          screen={size}
          onPublish={canPost ? () => void publish() : undefined}
          publishNote={publishNote}
          busy={publishing}
          onCancel={tools.cancel}
        />
      )}
      <Toolbar
        config={config}
        access={access}
        tool={tool}
        onTool={tools.setTool}
        onLogin={() => window.location.assign(LOGIN_URL)}
        draftKind={own?.kind ?? null}
        color={tools.color}
        onColor={tools.setColor}
        width={tools.width}
        onWidth={tools.setWidth}
        fontSize={tools.fontSize}
        onFontSize={tools.setFontSize}
      />
      {notice && (
        <p
          role="status"
          className="draft-notice"
          onClick={() => {
            setPublishError(null);
            tools.clearNotice();
          }}
        >
          {notice}
        </p>
      )}
      <Minimap config={config} cells={cells} view={visibleWorldRect(viewport, size)} />
    </div>
  );
}
