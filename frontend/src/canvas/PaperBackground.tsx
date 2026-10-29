import { useEffect, useState } from 'react';
import { Image, Rect } from 'react-konva';
import type { ClientConfig } from '../shared/pageConfig';
import backgroundUrl from './background.png';
import wallpaperUrl from './wallpaper.jpg';

interface Props {
  config: Pick<ClientConfig, 'width' | 'height'>;
}

// The center picture fits inside this share of the page, keeping its aspect ratio.
export const CENTER_PICTURE_FIT = 0.6;
export const CENTER_PICTURE_OPACITY = 0.2;

function useImage(url: string): HTMLImageElement | null {
  const [image, setImage] = useState<HTMLImageElement | null>(null);

  useEffect(() => {
    const img = new window.Image();
    img.onload = () => setImage(img);
    img.src = url;
    return () => {
      img.onload = null;
    };
  }, [url]);

  return image;
}

// Where the center picture goes: scaled to fit CENTER_PICTURE_FIT of the page, centered.
export function centerPictureBox(
  page: { width: number; height: number },
  picture: { width: number; height: number },
): { x: number; y: number; width: number; height: number } {
  const scale = Math.min(
    (page.width * CENTER_PICTURE_FIT) / picture.width,
    (page.height * CENTER_PICTURE_FIT) / picture.height,
  );
  const width = picture.width * scale;
  const height = picture.height * scale;
  return { x: (page.width - width) / 2, y: (page.height - height) / 2, width, height };
}

// Paper texture tiled over the whole page at its own size (replaces the D-07 cell grid),
// then the center picture on top of it, faded. Both sit below every doodle.
// Plain white shows until the images load, or if they fail to.
export default function PaperBackground({ config }: Props) {
  const { width, height } = config;
  const paper = useImage(wallpaperUrl);
  const picture = useImage(backgroundUrl);

  return (
    <>
      <Rect
        x={0}
        y={0}
        width={width}
        height={height}
        fill="#FFFFFF"
        fillPatternImage={paper ?? undefined}
        fillPatternRepeat="repeat"
        // Konva paints the color over the pattern unless told otherwise (fillPriority defaults to 'color').
        fillPriority="pattern"
        listening={false}
      />
      {picture && (
        <Image
          image={picture}
          {...centerPictureBox(config, { width: picture.naturalWidth, height: picture.naturalHeight })}
          opacity={CENTER_PICTURE_OPACITY}
          listening={false}
        />
      )}
    </>
  );
}
