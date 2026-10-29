import { useEffect, useState } from 'react';
import { fontInfo, loadFont } from './fonts';

// The font family to draw `text` with: the fallback until the font's slices for it are loaded, then the font.
// Konva measures text when its props change, so switching the family is what makes it redraw.
export function useFontFamily(id: string | null | undefined, text: string): string {
  const family = fontInfo(id).family;
  const [ready, setReady] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    void loadFont(id, text).then((ok) => {
      if (live && ok) {
        setReady(family);
      }
    });
    return () => {
      live = false;
    };
  }, [id, text, family]);
  return ready === family ? family : 'sans-serif';
}
