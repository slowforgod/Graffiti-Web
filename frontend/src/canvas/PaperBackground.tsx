import { useEffect, useState } from 'react';
import { Rect } from 'react-konva';
import type { ClientConfig } from '../shared/pageConfig';
import wallpaperUrl from './wallpaper.jpg';

interface Props {
  config: Pick<ClientConfig, 'width' | 'height'>;
}

// Paper texture tiled over the whole page at its own size (replaces the D-07 cell grid).
// Plain white shows until the image loads, or if it fails to.
export default function PaperBackground({ config }: Props) {
  const { width, height } = config;
  const [paper, setPaper] = useState<HTMLImageElement | null>(null);

  useEffect(() => {
    const img = new window.Image();
    img.onload = () => setPaper(img);
    img.src = wallpaperUrl;
    return () => {
      img.onload = null;
    };
  }, []);

  return (
    <Rect
      x={0}
      y={0}
      width={width}
      height={height}
      fill="#FFFFFF"
      fillPatternImage={paper ?? undefined}
      fillPatternRepeat="repeat"
      listening={false}
    />
  );
}
