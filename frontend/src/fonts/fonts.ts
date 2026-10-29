import { FONT_MANIFEST } from './manifest';

// The text doodle fonts (specs/13-text-fonts.md). Each is split into slices by unicode-range, so a text only
// downloads the letters it uses. Built by scripts/build_fonts.py from the originals, which stay out of git.
const files = import.meta.glob<string>('./files/*.woff2', { query: '?url', import: 'default', eager: true });
const previews = import.meta.glob<string>('./previews/*.png', { query: '?url', import: 'default', eager: true });

export interface FontInfo {
  id: string;
  label: string;
  // The CSS font-family list, with the browser's sans-serif for letters a font lacks.
  family: string;
  // The picker's sample image ("낙서 Graffiti" in the font).
  preview: string;
}

export const FONTS: FontInfo[] = FONT_MANIFEST.map((f) => ({
  id: f.id,
  label: f.label,
  family: `gf-${f.id}, sans-serif`,
  preview: previews[`./previews/${f.id}.png`] ?? '',
}));

// Texts from before fonts, and font ids this build does not know, are drawn in the first font, which is the
// server's default-font.
export function fontInfo(id: string | null | undefined): FontInfo {
  return FONTS.find((f) => f.id === id) ?? FONTS[0]!;
}

const registered = new Set<string>();

function register(id: string): void {
  if (registered.has(id) || typeof FontFace === 'undefined' || !document.fonts) {
    return;
  }
  registered.add(id);
  const entry = FONT_MANIFEST.find((f) => f.id === id);
  for (const slice of entry?.slices ?? []) {
    const url = files[`./files/${slice.file}`];
    if (url) {
      document.fonts.add(new FontFace(`gf-${id}`, `url(${url}) format('woff2')`, { unicodeRange: slice.range }));
    }
  }
}

// Resolves once the slices `text` needs are loaded: true when they are, false when they cannot be (no font
// support, a network error). Callers draw with the fallback until then and never fail on it.
export async function loadFont(id: string | null | undefined, text: string): Promise<boolean> {
  const font = fontInfo(id);
  register(font.id);
  if (!document.fonts) {
    return false;
  }
  try {
    await document.fonts.load(`16px ${font.family}`, text || ' ');
    return true;
  } catch {
    return false;
  }
}
