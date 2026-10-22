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
const WIDTH = 84;
const HEIGHT = 36;

// ✓ and ✗ at the top right of the draft (D-10).
export default function DraftActions({ bounds, viewport, screen, onPublish, publishNote, busy, onCancel }: Props) {
  const right = (bounds.x + bounds.w) * viewport.scale + viewport.x;
  const top = bounds.y * viewport.scale + viewport.y - HEIGHT;
  const left = Math.min(Math.max(0, right - WIDTH), screen.width - WIDTH);
  return (
    <div className="draft-actions" style={{ left, top: Math.min(Math.max(0, top), screen.height - HEIGHT) }}>
      <button type="button" aria-label="게시" title={publishNote} disabled={!onPublish || busy} onClick={onPublish}>
        ✓
      </button>
      <button type="button" aria-label="취소" disabled={busy} onClick={onCancel}>
        ✗
      </button>
      {publishNote && <span className="draft-note">{publishNote}</span>}
    </div>
  );
}
