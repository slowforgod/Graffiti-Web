import { useRef } from 'react';
import type { Rect, Size, Viewport } from '../canvas/viewport';
import { ICONS } from './icons';

interface Props {
  // The box the handle sits above: the dashed size square for a drawing, the text box for a text.
  bounds: Rect;
  viewport: Viewport;
  screen: Size;
  disabled?: boolean;
  // While a stroke is being drawn the pointer passes through, so a stroke under the handle goes on.
  passThrough?: boolean;
  // A world delta for each pointer move while the handle is held, then the end of the drag.
  onMove: (dx: number, dy: number) => void;
  onMoveEnd: () => void;
}

const SIZE = 32;
// Below the header and the page status, when the box's top is off screen.
const TOP_MIN = 48;

// The move handle just above the top left corner of the box, outside it so it never covers the drawing:
// drag it to move the whole draft.
export default function DraftMoveHandle(props: Props) {
  const { bounds, viewport, screen, disabled, passThrough, onMove, onMoveEnd } = props;
  const last = useRef<{ x: number; y: number } | null>(null);
  const left = bounds.x * viewport.scale + viewport.x;
  const top = bounds.y * viewport.scale + viewport.y - SIZE - 4;
  const end = () => {
    if (last.current) {
      last.current = null;
      onMoveEnd();
    }
  };
  return (
    <button
      type="button"
      className={passThrough ? 'draft-move icon-button pass-through' : 'draft-move icon-button'}
      aria-label="낙서 옮기기"
      title="끌어서 옮기기"
      disabled={disabled}
      style={{
        left: Math.min(Math.max(0, left), screen.width - SIZE),
        top: Math.min(Math.max(TOP_MIN, top), screen.height - SIZE),
      }}
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture?.(e.pointerId);
        last.current = { x: e.clientX, y: e.clientY };
      }}
      onPointerMove={(e) => {
        if (!last.current) {
          return;
        }
        const dx = (e.clientX - last.current.x) / viewport.scale;
        const dy = (e.clientY - last.current.y) / viewport.scale;
        // Whole world pixels only, so slow drags at high zoom still add up.
        if (Math.abs(Math.round(dx)) < 1 && Math.abs(Math.round(dy)) < 1) {
          return;
        }
        last.current = {
          x: last.current.x + Math.round(dx) * viewport.scale,
          y: last.current.y + Math.round(dy) * viewport.scale,
        };
        onMove(Math.round(dx), Math.round(dy));
      }}
      onPointerUp={end}
      onPointerCancel={end}
      onLostPointerCapture={end}
    >
      <img src={ICONS.move} alt="" draggable={false} />
    </button>
  );
}
