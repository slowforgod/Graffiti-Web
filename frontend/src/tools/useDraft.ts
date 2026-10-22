import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { Point } from '../canvas/viewport';
import type { RealtimeLink } from '../realtime/link';
import type { ClientConfig } from '../shared/pageConfig';
import type { Draft, DrawingDraft, TextDraft } from './draft';
import { eraseAt, extendStroke, inCanvas, newDrawing, startStroke, textLength } from './draft';
import { DraftSender } from './draftSender';
import { measureText } from './textMeasure';

export type Tool = 'move' | 'pen' | 'text' | 'eraser';

// Why the draft changed without the author asking, shown as a short message.
export type DraftNotice = 'too-big' | 'text-full' | 'idle' | 'disconnected';

// The eraser's reach on screen, in CSS pixels, whatever the zoom.
const ERASER_SCREEN_RADIUS = 8;

type Config = Pick<
  ClientConfig,
  'width' | 'height' | 'maxBbox' | 'strokeWidths' | 'defaultFontSize' | 'textMaxLength' | 'draftIdleTimeoutSec' | 'draftIntervalMs'
>;

interface Options {
  pageId: string;
  config: Config;
  link: RealtimeLink;
  newId?: () => string;
}

// The author's tools and the one draft they edit (D-07, D-10, D-11). The draft is mutated in place and
// `version` changes on every edit so the screen redraws.
export function useDraft({ pageId, config, link, newId = () => crypto.randomUUID() }: Options) {
  const [tool, setTool] = useState<Tool>('move');
  const [color, setColorState] = useState(0);
  const [width, setWidth] = useState(config.strokeWidths[Math.min(1, config.strokeWidths.length - 1)]!);
  const [fontSize, setFontSizeState] = useState(config.defaultFontSize);
  const [notice, setNotice] = useState<DraftNotice | null>(null);
  // Read from the link itself, so a connect before this hook listened is not missed.
  const online = useSyncExternalStore(
    useCallback(
      (changed: () => void) => {
        const stopConnect = link.onConnect(changed);
        const stopDisconnect = link.onDisconnect(changed);
        return () => {
          stopConnect();
          stopDisconnect();
        };
      },
      [link],
    ),
    () => link.connected(),
  );
  const [version, setVersion] = useState(0);
  // The state is for rendering; handlers read the ref so they see the draft of the same event.
  const [shown, setShown] = useState<Draft | null>(null);
  const draft = useRef<Draft | null>(null);
  const sender = useRef<DraftSender | null>(null);
  const stroke = useRef<number | null>(null);
  const idle = useRef<ReturnType<typeof setTimeout> | null>(null);

  const redraw = useCallback(() => setVersion((v) => v + 1), []);

  const drop = useCallback(
    (how: 'cancel' | 'close') => {
      if (how === 'cancel') {
        sender.current?.cancel();
      } else {
        sender.current?.close();
      }
      sender.current = null;
      draft.current = null;
      setShown(null);
      stroke.current = null;
      if (idle.current !== null) {
        clearTimeout(idle.current);
        idle.current = null;
      }
      redraw();
    },
    [redraw],
  );

  // Any edit restarts the idle clock; when it runs out the draft is cancelled (D-11).
  const touched = useCallback(() => {
    if (idle.current !== null) {
      clearTimeout(idle.current);
    }
    idle.current = setTimeout(() => {
      drop('cancel');
      setNotice('idle');
    }, config.draftIdleTimeoutSec * 1000);
    redraw();
  }, [config.draftIdleTimeoutSec, drop, redraw]);

  const begin = useCallback(
    <D extends Draft>(d: D): D => {
      draft.current = d;
      setShown(d);
      sender.current = new DraftSender({
        pageId,
        draftId: d.id,
        intervalMs: config.draftIntervalMs,
        send: (body) => link.send('/app/draft', body),
        stroke: (i) => (draft.current?.kind === 'DRAWING' ? (draft.current.strokes[i] ?? null) : null),
      });
      setNotice(null);
      return d;
    },
    [config.draftIntervalMs, link, pageId],
  );

  useEffect(() => {
    const stopDisconnect = link.onDisconnect(() => {
      // The server cancels the draft for the dropped connection; this screen forgets it too (D-11).
      if (draft.current) {
        drop('close');
        setNotice('disconnected');
      }
    });
    return stopDisconnect;
  }, [link, drop]);

  // Leaving the page (a reload after the page turned) drops the draft.
  useEffect(() => () => drop('cancel'), [drop]);

  const textBox = (text: string, size: number, at: Point) => {
    const { w, h } = measureText(text, size);
    const fits = w <= config.maxBbox && h <= config.maxBbox && at.x + w <= config.width && at.y + h <= config.height;
    return fits ? { w, h } : null;
  };

  const pointerDown = (p: Point, scale: number) => {
    const current = draft.current;
    if (tool === 'pen' && current?.kind !== 'TEXT') {
      const d = (current as DrawingDraft | null) ?? begin(newDrawing(newId()));
      const index = startStroke(d, p, color, width, config);
      if (index === null) {
        setNotice('too-big');
        if (d.strokes.length === 0) {
          drop('close');
        }
        return;
      }
      stroke.current = index;
      sender.current?.points(index);
      touched();
    } else if (tool === 'eraser' && current?.kind === 'DRAWING') {
      erase(current, p, scale);
    } else if (tool === 'text' && !current && inCanvas(p, config)) {
      const box = textBox('', fontSize, p);
      if (!box) {
        setNotice('too-big');
        return;
      }
      const d: TextDraft = begin({ id: newId(), kind: 'TEXT', text: '', c: color, size: fontSize, x: p.x, y: p.y, ...box });
      sender.current?.text({ x: d.x, y: d.y, w: d.w, h: d.h });
      touched();
    }
  };

  const pointerMove = (p: Point, scale: number) => {
    const current = draft.current;
    if (tool === 'pen' && current?.kind === 'DRAWING' && stroke.current !== null) {
      const result = extendStroke(current, stroke.current, p, config);
      if (result === 'added') {
        sender.current?.points(stroke.current);
        touched();
      } else if (result === 'too-big') {
        setNotice('too-big');
      }
    } else if (tool === 'eraser' && current?.kind === 'DRAWING') {
      erase(current, p, scale);
    }
  };

  const pointerUp = () => {
    stroke.current = null;
  };

  const erase = (d: DrawingDraft, p: Point, scale: number) => {
    const erased = eraseAt(d, p, ERASER_SCREEN_RADIUS / scale);
    if (erased.length > 0) {
      erased.forEach((i) => sender.current?.erase(i));
      touched();
    }
  };

  // Applies a new text (or size or colour) to the text draft when it stays within the limits (D-07, D-11).
  const editText = (next: { text?: string; size?: number; c?: number }): boolean => {
    const d = draft.current;
    if (d?.kind !== 'TEXT') {
      return false;
    }
    const text = next.text ?? d.text;
    const size = next.size ?? d.size;
    if (textLength(text) > config.textMaxLength) {
      setNotice('text-full');
      return false;
    }
    const box = textBox(text, size, d);
    if (!box) {
      setNotice('too-big');
      return false;
    }
    Object.assign(d, { text, size, c: next.c ?? d.c }, box);
    sender.current?.text({ x: d.x, y: d.y, w: d.w, h: d.h });
    touched();
    return true;
  };

  const setColor = (c: number) => {
    setColorState(c);
    editText({ c });
  };

  const setFontSize = (size: number) => {
    if (draft.current?.kind !== 'TEXT' || editText({ size })) {
      setFontSizeState(size);
    }
  };

  return {
    tool,
    setTool,
    color,
    setColor,
    width,
    setWidth,
    fontSize,
    setFontSize,
    notice,
    clearNotice: () => setNotice(null),
    online,
    draft: shown,
    version,
    pointerDown,
    pointerMove,
    pointerUp,
    editText: (text: string) => editText({ text }),
    cancel: () => drop('cancel'),
  };
}
