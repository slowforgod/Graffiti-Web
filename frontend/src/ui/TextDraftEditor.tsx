import { useEffect, useRef } from 'react';
import type { Viewport } from '../canvas/viewport';
import type { TextDraft } from '../tools/draft';
import { TEXT_FONT } from '../tools/textMeasure';

interface Props {
  draft: TextDraft;
  color: string;
  viewport: Viewport;
  onChange: (text: string) => boolean;
}

// Where the author types a text draft. The letters stay in this box until posted: other people see an
// empty "작성 중…" box (D-33). The value is plain text, never HTML (SEC-12).
export default function TextDraftEditor({ draft, color, viewport, onChange }: Props) {
  const box = useRef<HTMLTextAreaElement>(null);
  useEffect(() => box.current?.focus(), []);
  const s = viewport.scale;
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
        top: draft.y * s + viewport.y,
        width: draft.w * s + draft.size * s,
        height: draft.h * s,
        fontSize: draft.size * s,
        fontFamily: TEXT_FONT,
        color,
      }}
    />
  );
}
