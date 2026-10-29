import { fontInfo, FONTS } from '../fonts/fonts';
import type { ClientConfig } from '../shared/pageConfig';
import type { Tool } from '../tools/useDraft';
import FontPicker from './FontPicker';
import { ICONS } from './icons';

// Who may pick a drawing tool (API.md "작성자"): anyone signed in, consented and not banned, while connected.
export type DrawAccess = 'loading' | 'anonymous' | 'consent' | 'banned' | 'offline' | 'ok';

interface Props {
  config: Pick<ClientConfig, 'palette' | 'strokeWidthMin' | 'strokeWidthMax' | 'fontSizes' | 'fonts'>;
  access: DrawAccess;
  tool: Tool;
  onTool: (tool: Tool) => void;
  onLogin: () => void;
  // The kind of the open draft: the other kind's tools wait until it is posted or cancelled (D-10).
  draftKind: 'DRAWING' | 'TEXT' | null;
  color: number;
  onColor: (c: number) => void;
  // The width of the pen or the eraser, whichever is picked.
  width: number;
  onWidth: (w: number) => void;
  fontSize: number;
  onFontSize: (size: number) => void;
  font: string;
  onFont: (id: string) => void;
  // Opens the "내 낙서" panel (D-13); absent for people who cannot have doodles.
  onMine?: () => void;
  mineOpen?: boolean;
  // Zoom buttons for people without a wheel or pinch; absent when the screen offers none.
  zoom?: { percent: number; onIn?: () => void; onOut?: () => void };
}

// D-07 palette order. A palette that grows beyond it falls back to numbered names.
const COLOR_NAMES = ['검정', '흰색', '빨강', '주황', '노랑', '초록', '하늘', '파랑', '보라', '분홍', '갈색', '회색'];

const TOOLS: { tool: Tool; label: string; icon: string }[] = [
  { tool: 'move', label: '이동', icon: ICONS.pointer },
  { tool: 'pen', label: '펜', icon: ICONS.pencil },
  { tool: 'eraser', label: '지우개', icon: ICONS.eraser },
  { tool: 'text', label: '텍스트', icon: ICONS.text },
];

const ACCESS_HINT: Partial<Record<DrawAccess, string>> = {
  banned: '이용이 제한된 계정이에요.',
  offline: '연결 중이에요…',
};

// The server's fonts in its order, leaving out any this build has no files for.
function pickable(ids: string[]) {
  return ids.filter((id) => FONTS.some((f) => f.id === id)).map(fontInfo);
}

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
            className="icon-button"
            aria-label={t.label}
            title={t.label}
            aria-pressed={tool === t.tool}
            disabled={disabled(t.tool)}
            onClick={() => pick(t.tool)}
          >
            <img src={t.icon} alt="" />
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
      {(tool === 'pen' || tool === 'eraser') && (
        <label className="toolbar-group width-slider">
          굵기
          <input
            type="range"
            min={config.strokeWidthMin}
            max={config.strokeWidthMax}
            step={1}
            value={props.width}
            onChange={(e) => props.onWidth(Number(e.target.value))}
          />
          <span className="width-value">{props.width}px</span>
        </label>
      )}
      {tool === 'text' && (
        <div className="toolbar-group">
          <label>
            글자 크기{' '}
            <select value={props.fontSize} onChange={(e) => props.onFontSize(Number(e.target.value))}>
              {config.fontSizes.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <FontPicker fonts={pickable(config.fonts)} value={props.font} onChange={props.onFont} />
        </div>
      )}
      {props.onMine && (
        <div className="toolbar-group">
          <button
            type="button"
            className="icon-button"
            aria-label="내 낙서"
            title="내 낙서"
            aria-pressed={props.mineOpen ?? false}
            disabled={draftKind !== null}
            onClick={props.onMine}
          >
            <img src={ICONS.find} alt="" />
          </button>
        </div>
      )}
      {hint && <span className="toolbar-hint">{hint}</span>}
      {props.zoom && (
        <div className="toolbar-group" aria-label="확대·축소">
          <button
            type="button"
            className="icon-button"
            aria-label="축소"
            title="축소"
            disabled={!props.zoom.onOut}
            onClick={props.zoom.onOut}
          >
            <img src={ICONS.minus} alt="" />
          </button>
          <span className="zoom-level">{props.zoom.percent}%</span>
          <button
            type="button"
            className="icon-button"
            aria-label="확대"
            title="확대"
            disabled={!props.zoom.onIn}
            onClick={props.zoom.onIn}
          >
            <img src={ICONS.plus} alt="" />
          </button>
        </div>
      )}
    </div>
  );
}
