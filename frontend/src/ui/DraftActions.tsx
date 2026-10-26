import type { Rect, Size, Viewport } from '../canvas/viewport';

interface Props {
  bounds: Rect;
  viewport: Viewport;
  screen: Size;
  // Absent while posting is not possible; `publishNote` then says why, if there is a reason to show.
  onPublish?: () => void;
  publishNote?: string;
  // While a post is on its way neither button does anything.
  busy?: boolean;
  onCancel: () => void;
}

// Room the two buttons take on screen, so they stay inside it.
const WIDTH = 112;
const HEIGHT = 36;

// "게시" and "취소" at the top right of the draft (D-10). On a narrow draft they start at its left edge so
// they keep clear of the move handle.
export default function DraftActions({ bounds, viewport, screen, onPublish, publishNote, busy, onCancel }: Props) {
  const right = (bounds.x + bounds.w) * viewport.scale + viewport.x;
  const leftEdge = bounds.x * viewport.scale + viewport.x;
  const top = bounds.y * viewport.scale + viewport.y - HEIGHT;
  const left = Math.min(Math.max(0, leftEdge, right - WIDTH), screen.width - WIDTH);
  return (
    <div className="draft-actions" style={{ left, top: Math.min(Math.max(0, top), screen.height - HEIGHT) }}>
      <button type="button" title={publishNote} disabled={!onPublish || busy} onClick={onPublish}>
        게시
      </button>
      <button type="button" disabled={busy} onClick={onCancel}>
        취소
      </button>
      {publishNote && <span className="draft-note">{publishNote}</span>}
    </div>
  );
}
