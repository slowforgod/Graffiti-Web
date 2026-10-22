import type { ClientConfig } from '../shared/pageConfig';
import type { Tool } from '../tools/useDraft';

// Who may pick a drawing tool (API.md "작성자"): anyone signed in, consented and not banned, while connected.
export type DrawAccess = 'loading' | 'anonymous' | 'consent' | 'banned' | 'offline' | 'ok';

interface Props {
  config: Pick<ClientConfig, 'palette' | 'strokeWidths' | 'fontSizes'>;
  access: DrawAccess;
  tool: Tool;
  onTool: (tool: Tool) => void;
  onLogin: () => void;
  // The kind of the open draft: the other kind's tools wait until it is posted or cancelled (D-10).
  draftKind: 'DRAWING' | 'TEXT' | null;
  color: number;
  onColor: (c: number) => void;
  width: number;
  onWidth: (w: number) => void;
  fontSize: number;
  onFontSize: (size: number) => void;
}

// D-07 palette order. A palette that grows beyond it falls back to numbered names.
const COLOR_NAMES = ['검정', '흰색', '빨강', '주황', '노랑', '초록', '하늘', '파랑', '보라', '분홍', '갈색', '회색'];

const TOOLS: { tool: Tool; label: string }[] = [
  { tool: 'move', label: '이동' },
  { tool: 'pen', label: '펜' },
  { tool: 'text', label: '텍스트' },
  { tool: 'eraser', label: '지우개' },
];

const ACCESS_HINT: Partial<Record<DrawAccess, string>> = {
  banned: '이용이 제한된 계정이에요.',
  offline: '연결 중이에요…',
};

export default function Toolbar(props: Props) {
  const { config, access, tool, onTool, onLogin, draftKind } = props;

  const pick = (next: Tool) => {
    if (next !== 'move' && access === 'anonymous') {
      // D-21: logging in starts when the pen is first picked up.
      onLogin();
      return;
    }
    onTool(next);
  };

  const disabled = (t: Tool) => {
    if (t === 'move') {
      return false;
    }
    if (access !== 'ok' && access !== 'anonymous') {
      return true;
    }
    if (t === 'text') {
      return draftKind === 'DRAWING';
    }
    if (t === 'eraser') {
      return draftKind !== 'DRAWING';
    }
    return draftKind === 'TEXT';
  };

  const hint = ACCESS_HINT[access];
  return (
    <div className="toolbar" role="toolbar" aria-label="그리기 도구">
      <div className="toolbar-group">
        {TOOLS.map((t) => (
          <button
            key={t.tool}
            type="button"
            aria-pressed={tool === t.tool}
            disabled={disabled(t.tool)}
            onClick={() => pick(t.tool)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {(tool === 'pen' || tool === 'text') && (
        <div className="toolbar-group" role="radiogroup" aria-label="색">
          {config.palette.map((hex, i) => (
            <button
              key={hex + i}
              type="button"
              role="radio"
              aria-checked={props.color === i}
              aria-label={COLOR_NAMES[i] ?? `색 ${i + 1}`}
              className="swatch"
              style={{ background: hex }}
              onClick={() => props.onColor(i)}
            />
          ))}
        </div>
      )}
      {tool === 'pen' && (
        <div className="toolbar-group" role="radiogroup" aria-label="굵기">
          {config.strokeWidths.map((w) => (
            <button key={w} type="button" role="radio" aria-checked={props.width === w} onClick={() => props.onWidth(w)}>
              {w}px
            </button>
          ))}
        </div>
      )}
      {tool === 'text' && (
        <label className="toolbar-group">
          글자 크기{' '}
          <select value={props.fontSize} onChange={(e) => props.onFontSize(Number(e.target.value))}>
            {config.fontSizes.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
      )}
      {hint && <span className="toolbar-hint">{hint}</span>}
    </div>
  );
}
