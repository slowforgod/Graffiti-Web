import { useEffect, useRef } from 'react';
import type { Viewport } from '../canvas/viewport';
import type { TextDraft } from '../tools/draft';
import { fontInfo } from '../fonts/fonts';

interface Props {
  draft: TextDraft;
  color: string;
  viewport: Viewport;
  onChange: (text: string) => boolean;
}

// Room above and below the line, in font sizes. A textarea cuts off whatever leaves its box, and with a line
// height of 1 the tall letters of some fonts (Jalnan) reach past it; the canvas draws them uncut.
const EDGE_EM = 0.25;

// Where the author types a text draft. The letters stay in this box until posted: other people see an
// empty "작성 중…" box (D-33). The value is plain text, never HTML (SEC-12).
export default function TextDraftEditor({ draft, color, viewport, onChange }: Props) {
  const box = useRef<HTMLTextAreaElement>(null);
  useEffect(() => box.current?.focus(), []);
  const s = viewport.scale;
  const edge = Math.ceil(draft.size * s * EDGE_EM);
  return (
    <textarea
      ref={box}
      aria-label="낙서 글자"
      className="text-draft"
      value={draft.text}
      spellCheck={false}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => e.stopPropagation()}
      style={{
        left: draft.x * s + viewport.x,
        top: draft.y * s + viewport.y - edge,
        width: draft.w * s + draft.size * s,
        height: draft.h * s,
        paddingTop: edge,
        paddingBottom: edge,
        fontSize: draft.size * s,
        fontFamily: fontInfo(draft.f).family,
        color,
      }}
    />
  );
}
