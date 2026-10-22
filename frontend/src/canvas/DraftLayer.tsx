import { Circle, Group, Line, Rect, Text } from 'react-konva';
import type { Draft } from '../tools/draft';
import type { RemoteDraft } from './remoteDrafts';

interface Props {
  // Mutated in place while edited: its points are copied on every render so Konva sees the change.
  own: Draft | null;
  others: RemoteDraft[];
  palette: string[];
  scale: number;
}

// How see-through other people's drafts are (D-11).
const OTHERS_OPACITY = 0.5;

function Stroke({ c, w, p, palette }: { c: number; w: number; p: number[]; palette: string[] }) {
  const color = palette[c] ?? palette[0];
  return p.length === 2 ? (
    <Circle x={p[0]} y={p[1]} radius={w / 2} fill={color} perfectDrawEnabled={false} />
  ) : (
    <Line points={p} stroke={color} strokeWidth={w} lineCap="round" lineJoin="round" perfectDrawEnabled={false} />
  );
}

// Drafts on top of the posted doodles. The author's own text draft is shown by its input box instead.
export default function DraftLayer({ own, others, palette, scale }: Props) {
  return (
    <>
      {others.map((d) => (
        <Group key={d.draftId} opacity={OTHERS_OPACITY}>
          {d.segments.map((s, i) => (
            <Stroke key={i} c={s.c} w={s.w} p={s.p} palette={palette} />
          ))}
          {d.box && (
            <>
              <Rect
                x={d.box.x}
                y={d.box.y}
                width={d.box.w}
                height={d.box.h}
                stroke="#5C6BC0"
                strokeWidth={1 / scale}
                dash={[4 / scale, 4 / scale]}
              />
              <Text x={d.box.x} y={d.box.y} text="작성 중…" fontSize={12 / scale} padding={2 / scale} fill="#5C6BC0" />
            </>
          )}
        </Group>
      ))}
      {own?.kind === 'DRAWING' && (
        <Group>
          {own.strokes.map((s, i) => s && <Stroke key={i} c={s.c} w={s.w} p={s.p.slice()} palette={palette} />)}
        </Group>
      )}
    </>
  );
}
