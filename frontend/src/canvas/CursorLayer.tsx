import { Circle, Group, Label, Tag, Text } from 'react-konva';
import type { RemoteCursor } from './cursors';

interface Props {
  cursors: RemoteCursor[];
  scale: number;
}

// Other people's cursors: a dot and the nickname, kept the same size on screen at any zoom.
export default function CursorLayer({ cursors, scale }: Props) {
  const unit = 1 / scale;
  return (
    <>
      {cursors.map((c) => (
        <Group key={c.sid} x={c.x} y={c.y} scaleX={unit} scaleY={unit}>
          <Circle radius={5} fill={c.color} stroke="#fff" strokeWidth={1.5} />
          <Label x={8} y={6}>
            <Tag fill={c.color} cornerRadius={4} />
            <Text text={c.nickname} fontSize={12} padding={4} fill="#fff" />
          </Label>
        </Group>
      ))}
    </>
  );
}
