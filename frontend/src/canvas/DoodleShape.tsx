import { Circle, Group, Line, Text } from 'react-konva';
import type { Doodle } from './doodles';

interface Props {
  doodle: Doodle;
  palette: string[];
}

// User text is drawn by Konva on the canvas, never put into the DOM (SEC-12).
export default function DoodleShape({ doodle, palette }: Props) {
  const color = (c: number) => palette[c] ?? palette[0];
  if (doodle.kind === 'TEXT') {
    const t = doodle.payload;
    return <Text x={t.x} y={t.y} text={t.text} fontSize={t.size} fill={color(t.c)} listening={false} />;
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
