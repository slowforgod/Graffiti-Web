import type { Rect, Size, Viewport } from '../canvas/viewport';

interface Props {
  // The box the buttons sit above: the dashed size square for a drawing, the text box for a text.
  bounds: Rect;
  viewport: Viewport;
  screen: Size;
  // Absent while posting is not possible; `publishNote` then says why, if there is a reason to show.
  onPublish?: () => void;
  publishNote?: string;
  // While a post is on its way neither button does anything.
  busy?: boolean;
  // While a stroke is being drawn the pointer passes through, so a stroke under the buttons goes on.
  passThrough?: boolean;
  onCancel: () => void;
}

// Room the two buttons take on screen, so they stay inside it.
const WIDTH = 112;
const HEIGHT = 36;
const GAP = 4;
// Below the header and the page status, when the box's top is off screen.
const TOP_MIN = 48;

// "게시" and "취소" just above the top right corner of the box (D-10), outside it so they never cover
// the drawing. On a narrow box they start at its left edge so they keep clear of the move handle.
export default function DraftActions(props: Props) {
  const { bounds, viewport, screen, onPublish, publishNote, busy, onCancel, passThrough } = props;
  const right = (bounds.x + bounds.w) * viewport.scale + viewport.x;
  const leftEdge = bounds.x * viewport.scale + viewport.x;
  const top = bounds.y * viewport.scale + viewport.y - HEIGHT - GAP;
  const left = Math.min(Math.max(0, leftEdge, right - WIDTH), screen.width - WIDTH);
  return (
    <div
      className={passThrough ? 'draft-actions pass-through' : 'draft-actions'}
      style={{ left, top: Math.min(Math.max(TOP_MIN, top), screen.height - HEIGHT) }}
    >
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
