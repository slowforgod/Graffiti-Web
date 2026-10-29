import { Circle, Group, Line, Text } from 'react-konva';
import { useFontFamily } from '../fonts/useFontReady';
import type { Doodle } from './doodles';

interface Props {
  doodle: Doodle;
  palette: string[];
}

// User text is drawn by Konva on the canvas, never put into the DOM (SEC-12).
export default function DoodleShape({ doodle, palette }: Props) {
  const color = (c: number) => palette[c] ?? palette[0];
  if (doodle.kind === 'TEXT') {
    return <TextShape payload={doodle.payload} fill={color(doodle.payload.c)} />;
  }
  return (
    <Group listening={false}>
      {doodle.payload.strokes.map((s, i) =>
        s.p.length === 2 ? (
          <Circle key={i} x={s.p[0]} y={s.p[1]} radius={s.w / 2} fill={color(s.c)} perfectDrawEnabled={false} />
        ) : (
          <Line
            key={i}
            points={s.p}
            stroke={color(s.c)}
            strokeWidth={s.w}
            lineCap="round"
            lineJoin="round"
            perfectDrawEnabled={false}
          />
        ),
      )}
    </Group>
  );
}

type TextPayload = Extract<Doodle, { kind: 'TEXT' }>['payload'];

// Drawn in the fallback font until the text's font has loaded, then again in it (13).
function TextShape({ payload: t, fill }: { payload: TextPayload; fill: string | undefined }) {
  const family = useFontFamily(t.f, t.text);
  return <Text x={t.x} y={t.y} text={t.text} fontSize={t.size} fontFamily={family} fill={fill} listening={false} />;
}
