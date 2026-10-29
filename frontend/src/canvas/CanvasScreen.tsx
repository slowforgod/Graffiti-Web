import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import type { Me } from '../auth/me';
import { LOGIN_URL } from '../auth/me';
import type { RealtimeLink } from '../realtime/link';
import type { Connection } from '../realtime/connection';
import { createConnection } from '../realtime/connection';
import type { PageStatusEvent } from '../realtime/metaSync';
import { MetaSync } from '../realtime/metaSync';
import { throttle } from '../realtime/throttle';
import type { CurrentPage } from '../shared/pageConfig';
import { fetchCurrentPage } from '../shared/pageConfig';
import { clockOffset, cooldownLeft, endsAfter, minutesLeft } from '../tools/cooldown';
import { draftBounds } from '../tools/draft';
import { isHeld, publishBody, publishDoodle } from '../tools/publish';
import { sizeGuide } from '../tools/sizeGuide';
import type { DraftNotice } from '../tools/useDraft';
import { useDraft } from '../tools/useDraft';
import { useMyDoodles } from '../mine/useMyDoodles';
import DraftActions from '../ui/DraftActions';
import DraftMoveHandle from '../ui/DraftMoveHandle';
import MyDoodlesPanel from '../ui/MyDoodlesPanel';
import Minimap from '../ui/Minimap';
import StatusBar from '../ui/StatusBar';
import TurnWarning from '../ui/TurnWarning';
import { nextTick, untilEnd } from '../ui/pageTime';
import TextDraftEditor from '../ui/TextDraftEditor';
import type { DrawAccess } from '../ui/Toolbar';
import Toolbar from '../ui/Toolbar';
import { fetchMinimap } from '../ui/minimapApi';
import CanvasView from './CanvasView';
import { ChunkSync } from './chunkSync';
import { visibleChunks } from './chunks';
import type { RemoteCursor } from './cursors';
import type { Doodle } from './doodles';
import type { RemoteDraft } from './remoteDrafts';
import { fetchChunkDoodles } from './doodles';
import { useWindowSize } from './useWindowSize';
import type { Point, Rect, Viewport } from './viewport';
import { centerOn, clampScale, visibleWorldRect, zoomAt } from './viewport';

// Waits for panning or zooming to settle before asking for new chunks.
const CHUNK_DEBOUNCE_MS = 100;

// The zoom buttons step by 30 percentage points (100% → 130% → 160%…), about the screen centre.
const ZOOM_STEP = 0.3;

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

// A held post was taken: it goes on the canvas once an admin approves it.
const PUBLISH_HELD = '검토 후 게시돼요.';

// A text with profanity went up, but at the smallest size (D-43).
const PUBLISH_SHRUNK = '욕설이 있어 글자를 작게 줄여 게시했어요.';

// Longest wait between redraws of the cooldown note.
const COOLDOWN_TICK_MS = 30_000;

// After the scheduled end, how long to wait for page:turned before asking for the current page anyway
// (a connection that missed the event).
const TURN_GRACE_MS = 5_000;

// Shown on the new page after a turn (D-16).
const TURNED = '새 페이지로 넘어왔어요.';
const TURNED_DRAFT_LOST = '페이지가 넘어가 그리던 초안이 사라졌어요.';

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
  const [turnNotice, setTurnNotice] = useState<string | null>(null);
  // A page that ended under the screen: say so on the next one, and whether a draft went with it.
  const turned = useCallback(
    (hadDraft: boolean) => {
      setTurnNotice(hadDraft ? TURNED_DRAFT_LOST : TURNED);
      reload();
    },
    [reload],
  );
  const [connection] = useState<Connection>(() => createConnection());
  const heartbeatMs = page?.config.heartbeatMs;

  useEffect(() => {
    if (heartbeatMs === undefined) {
      return;
    }
    connection.activate(heartbeatMs);
    const stop = connection.onPageEnded(() => turned(false));
    return () => {
      stop();
      connection.deactivate();
    };
  }, [connection, heartbeatMs, turned]);

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
  return (
    <PageCanvas key={page.id} page={page} link={connection} me={me} onPageEnded={turned} turnNotice={turnNotice} />
  );
}

interface PageCanvasProps {
  page: CurrentPage;
  link: RealtimeLink;
  me?: Me;
  // `hadDraft`: the author was drawing when the page ended, and the draft is gone (D-16).
  onPageEnded: (hadDraft: boolean) => void;
  turnNotice: string | null;
}

function PageCanvas({ page, link, me, onPageEnded: pageEnded, turnNotice }: PageCanvasProps) {
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
  const hasDraft = useRef(false);
  useEffect(() => {
    hasDraft.current = tools.draft !== null;
  }, [tools.draft]);
  const onPageEnded = useCallback(() => pageEnded(hasDraft.current), [pageEnded]);
  const [turnNote, setTurnNote] = useState(turnNotice);
  // The status bar and the T−5 countdown (D-16) follow page:status; occupancy follows the minimap diffs.
  const [status, setStatus] = useState<Pick<PageStatusEvent, 'status' | 'scheduledEndAt'>>(() => ({
    status: page.status,
    scheduledEndAt: page.scheduledEndAt,
  }));
  const [occupancy, setOccupancy] = useState(page.occupancyPermille);
  const [offset] = useState(() => clockOffset(page.serverTime));
  // The cooldown this screen learned from its own posts; until then the one from /api/me.
  const [postedCooldown, setPostedCooldown] = useState<string | null>();
  const [publishing, setPublishing] = useState(false);
  // A pen stroke is under way: the buttons over the canvas let the pointer through until it ends.
  const [stroking, setStroking] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  // The same page loaded again (the end passed without page:turned) brings the server's latest times.
  const [loaded, setLoaded] = useState(page);
  if (loaded !== page) {
    setLoaded(page);
    setStatus({ status: page.status, scheduledEndAt: page.scheduledEndAt });
    setOccupancy(page.occupancyPermille);
  }
  const removeOwn = useCallback((ids: string[]) => sync.current?.removeOwn(ids), []);
  const mine = useMyDoodles({ pageId: page.id, seq: page.seq, config, onDeleted: removeOwn, onPageEnded });

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
      onMeta: (m) => {
        setCells(m.cells);
        setOccupancy(m.occupancyPermille);
      },
      onStatus: (e) => {
        setStatus({ status: e.status, scheduledEndAt: e.scheduledEndAt });
        setOccupancy(e.occupancyPermille);
      },
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
  // The panel needs the canvas to pick doodles, so drawing rests while it is open.
  const tool = access === 'ok' && !mine.open ? tools.tool : 'move';
  // Anyone signed in and consented has doodles to look at; banned authors may not delete them (API.md §3).
  const hasMine = me?.authenticated === true && !me.consentRequired;
  // With the pen, a dashed square shows how big one drawing may be. It follows the middle of the view
  // until it is held: once the drawing has strokes, or once it was dragged by its move handle (`placed`).
  // While a stroke is drawn it stays still; when the stroke ends it moves just enough to take the drawing in.
  const drawingBounds = own?.kind === 'DRAWING' ? bounds : null;
  const [heldGuide, setHeldGuide] = useState<Rect | null>(null);
  const [placed, setPlaced] = useState(false);
  const penStroke = stroking && tool === 'pen';
  const guide =
    tool === 'pen' || drawingBounds
      ? sizeGuide(heldGuide, penStroke ? null : drawingBounds, visibleWorldRect(viewport, size), config)
      : null;
  const nextHeld = drawingBounds || penStroke || (placed && tool === 'pen') ? guide : null;
  // A drawing's buttons sit on the dashed square. A stroke may cross them; they let the pointer through then.
  const controlsBox = drawingBounds ? guide : null;
  if (!sameRect(nextHeld, heldGuide)) {
    setHeldGuide(nextHeld);
  }
  if (placed && tool !== 'pen' && !drawingBounds) {
    setPlaced(false);
  }
  // The move handle sits on the open draft, or on the empty dashed square while the pen is picked.
  const handleBox = controlsBox ?? bounds ?? (tool === 'pen' ? guide : null);

  // The handle drags a text by itself, and a drawing together with its square. Without strokes it drags the
  // square alone, which then stays where it was put.
  const moveBy = (dx: number, dy: number) => {
    if (own?.kind === 'TEXT') {
      tools.moveBy(dx, dy);
      return;
    }
    const moved = drawingBounds ? tools.moveBy(dx, dy) : { x: dx, y: dy };
    const from = guide;
    if (!moved || !from) {
      return;
    }
    const max = { x: Math.max(0, config.width - from.w), y: Math.max(0, config.height - from.h) };
    setHeldGuide((g) => {
      const base = g ?? from;
      return {
        ...base,
        x: Math.min(max.x, Math.max(0, base.x + moved.x)),
        y: Math.min(max.y, Math.max(0, base.y + moved.y)),
      };
    });
    if (!drawingBounds) {
      setPlaced(true);
    }
  };
  const sendCursor = useMemo(
    () =>
      author &&
      throttle<Point>(config.cursorIntervalMs, (p) => link.send('/app/cursor', { pageId: page.id, x: p.x, y: p.y })),
    [author, config.cursorIntervalMs, link, page.id],
  );
  useEffect(() => () => sendCursor?.cancel(), [sendCursor]);
  const cooldownEndsAt = postedCooldown !== undefined ? postedCooldown : (author?.cooldownEndsAt ?? null);
  const waitMs = cooldownLeft(cooldownEndsAt, offset, now);

  const endMs = untilEnd(status.scheduledEndAt, offset, now);
  const warningMs = config.countdownWarningSec * 1000;

  // Keeps the status bar and the countdown current. Past the end, page:turned should come at once; if it
  // does not (a missed event), the current page is asked for after a grace period.
  useEffect(() => {
    const timer =
      endMs > 0
        ? setTimeout(() => setNow(Date.now()), Math.min(endMs, nextTick(endMs, warningMs)))
        : setTimeout(onPageEnded, TURN_GRACE_MS);
    return () => clearTimeout(timer);
  }, [endMs, warningMs, onPageEnded]);

  // Redraws the note as minutes pass and enables ✓ when the cooldown ends.
  useEffect(() => {
    if (waitMs <= 0) {
      return;
    }
    const timer = setTimeout(() => setNow(Date.now()), Math.min(waitMs, COOLDOWN_TICK_MS));
    return () => clearTimeout(timer);
  }, [waitMs]);

  const publish = async () => {
    if (!own || !publishBody(own) || publishing) {
      return;
    }
    setPublishing(true);
    setPublishError(null);
    try {
      // A text goes with the box of its own font, which may still be loading (13).
      const settling = tools.settle();
      if (settling) {
        await settling;
      }
      const result = await publishDoodle(page.id, publishBody(own)!);
      if (isHeld(result)) {
        setPublishError(PUBLISH_HELD);
      } else {
        sync.current?.addPublished(result.doodle);
        if (result.shrunk) {
          setPublishError(PUBLISH_SHRUNK);
        }
      }
      setPostedCooldown(result.cooldownEndsAt);
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
      // A rejection keeps the draft so it can be changed, but the post is used up (D-12).
      if (e instanceof ApiError && e.code === 'MODERATION_REJECTED' && typeof e.details.cooldownEndsAt === 'string') {
        setPostedCooldown(e.details.cooldownEndsAt);
      }
      setPublishError(e instanceof ApiError && e.message ? e.message : PUBLISH_FAILED);
    } finally {
      setPublishing(false);
      setNow(Date.now());
    }
  };

  const zoomTo = (scale: number) =>
    setViewport((vp) => zoomAt(vp, { x: size.width / 2, y: size.height / 2 }, scale, size, config));
  // Rounded to the step so a wheel zoom in between lands back on 10% marks.
  const zoomIn = clampScale(Math.round((viewport.scale + ZOOM_STEP) * 10) / 10, config);
  const zoomOut = clampScale(Math.round((viewport.scale - ZOOM_STEP) * 10) / 10, config);

  const canPost = own !== null && publishBody(own) !== null && waitMs <= 0;
  const publishNote = waitMs > 0 ? `${minutesLeft(waitMs)}분 뒤에 게시할 수 있어요` : undefined;
  const notice = publishError ?? (tools.notice ? NOTICES[tools.notice] : null) ?? turnNote;

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
        sizeGuide={guide}
        otherDrafts={drafts}
        onDrawStart={(p) => {
          setStroking(true);
          tools.pointerDown(p);
        }}
        onDrawMove={tools.pointerMove}
        onDrawEnd={() => {
          setStroking(false);
          tools.pointerUp();
        }}
        selection={
          mine.open && mine.items ? { items: mine.items, selected: mine.selected, onToggle: mine.toggle } : null
        }
      />
      {own?.kind === 'TEXT' && (
        <TextDraftEditor
          draft={own}
          color={config.palette[own.c] ?? config.palette[0]!}
          viewport={viewport}
          onChange={tools.editText}
        />
      )}
      {handleBox && (
        <DraftMoveHandle
          bounds={handleBox}
          passThrough={stroking}
          viewport={viewport}
          screen={size}
          disabled={publishing}
          onMove={moveBy}
          onMoveEnd={tools.moveEnd}
        />
      )}
      {own && bounds && (
        <DraftActions
          bounds={controlsBox ?? bounds}
          passThrough={stroking}
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
        font={tools.font}
        onFont={tools.setFont}
        onMine={hasMine ? () => (mine.open ? mine.close() : void mine.show()) : undefined}
        mineOpen={mine.open}
        zoom={{
          percent: Math.round(viewport.scale * 100),
          onIn: zoomIn > viewport.scale ? () => zoomTo(zoomIn) : undefined,
          onOut: zoomOut < viewport.scale ? () => zoomTo(zoomOut) : undefined,
        }}
      />
      {mine.open && <MyDoodlesPanel mine={mine} canDelete={author !== null} />}
      {notice && (
        <p
          role="status"
          className="draft-notice"
          onClick={() => {
            setPublishError(null);
            setTurnNote(null);
            tools.clearNotice();
          }}
        >
          {notice}
        </p>
      )}
      <Minimap config={config} cells={cells} view={visibleWorldRect(viewport, size)} />
      <StatusBar
        seq={page.seq}
        status={status.status}
        occupancyPermille={occupancy}
        createdAt={page.createdAt}
        remainingMs={endMs}
        thresholdPermille={config.countdownThresholdPermille}
        countdownMs={config.countdownDurationSec * 1000}
      />
      {endMs > 0 && endMs <= warningMs && <TurnWarning remainingMs={endMs} />}
    </div>
  );
}

function sameRect(a: Rect | null, b: Rect | null): boolean {
  return a === b || (!!a && !!b && a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h);
}
