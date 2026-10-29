"""Builds the text doodle fonts (specs/13-text-fonts.md §5.3, §5.6).

Reads the original font files from --src (they stay out of git: most free Korean fonts forbid passing the
file itself on), checks each against D-31, and writes into src/fonts/:
  files/<id>-<n>.woff2   one slice per character group, loaded only when a text needs it
  previews/<id>.png      the picker's sample image
  manifest.ts            id, label, family and each slice's unicode-range

Run: python -I scripts/build_fonts.py --src <folder with the originals>   (from frontend/)
"""

import argparse
import io
import shutil
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from PIL import Image, ImageDraw, ImageFont

# Order is the picker's order. The first is the fallback for texts without a font (DoodleShape).
FONTS = [
    {'id': 'pretendard', 'label': 'Pretendard', 'file': 'English_font/PretendardVariable.ttf', 'wght': 400},
    {'id': 'gowun-dodum', 'label': '고운돋움', 'file': 'Korean_font/GowunDodum-Regular.ttf'},
    {'id': 'galmuri', 'label': '갈무리', 'file': 'Korean_font/Galmuri11-Condensed.ttf'},
    {'id': 'jalnan', 'label': '잘난체', 'file': 'Korean_font/Jalnan2TTF.ttf'},
    {'id': 'kyobo-hand', 'label': '교보 손글씨', 'file': 'Korean_font/KyoboHandwriting2025lyb.ttf'},
    {'id': 'cafe24-ssurround', 'label': '카페24 써라운드', 'file': 'Korean_font/Cafe24Ssurround-v2.0.ttf'},
]

# D-31 with the 13 width factor: no glyph wider than this many em, no line taller than 1.4 em.
MAX_ADVANCE_EM = 1.25
MAX_HEIGHT_EM = 1.4

LATIN = [(0x20, 0x7E), (0xA0, 0xFF), (0x2010, 0x2027), (0x2030, 0x205E), (0x2190, 0x21FF), (0x2600, 0x27BF),
         (0x3000, 0x303F), (0x3131, 0x318E), (0xFF01, 0xFF60)]
HANGUL = range(0xAC00, 0xD7A4)
# Hangul in runs of whole code points, so each slice is one unicode-range and the manifest stays small.
HANGUL_SLICES = 40

PREVIEW_TEXT = '낙서 Graffiti'
PREVIEW_HEIGHT = 48


def split(cps, n):
    size = -(-len(cps) // n)
    return [cps[i:i + size] for i in range(0, len(cps), size)]


def runs(cps):
    """Code points as unicode-range runs."""
    out = []
    for cp in sorted(cps):
        if out and out[-1][1] == cp - 1:
            out[-1][1] = cp
        else:
            out.append([cp, cp])
    return ', '.join(f'U+{a:X}' if a == b else f'U+{a:X}-{b:X}' for a, b in out)


def slices(font):
    cmap = font.getBestCmap()
    upm = font['head'].unitsPerEm
    # Symbols wider than D-31 allows are left to the browser's fallback font rather than overflow the box.
    fits = {cp for cp in cmap if font['hmtx'][cmap[cp]][0] / upm <= MAX_ADVANCE_EM}
    latin = [cp for a, b in LATIN for cp in range(a, b + 1) if cp in fits]
    return [latin] + split(list(HANGUL), HANGUL_SLICES)


def check(font, name):
    """D-31: the server's bbox limits assume every glyph fits them (§5.6 step 2)."""
    upm = font['head'].unitsPerEm
    cmap = font.getBestCmap()
    hmtx = font['hmtx']
    used = [cp for cp in range(0x20, 0x7F) if cp in cmap] + [cp for cp in HANGUL if cp in cmap]
    widest = max(used, key=lambda cp: hmtx[cmap[cp]][0])
    advance = hmtx[cmap[widest]][0] / upm
    # Ink, not the line metrics: Konva puts lines one font size apart, so tall ascenders only overflow.
    glyf = font['glyf']
    tops = [getattr(glyf[cmap[cp]], 'yMax', 0) for cp in used]
    bottoms = [getattr(glyf[cmap[cp]], 'yMin', 0) for cp in used]
    height = (max(tops) - min(bottoms)) / upm
    if advance > MAX_ADVANCE_EM or height > MAX_HEIGHT_EM:
        sys.exit(f'{name}: {chr(widest)!r} is {advance:.2f} em wide, lines {height:.2f} em high '
                 f'(limits {MAX_ADVANCE_EM}, {MAX_HEIGHT_EM})')
    if len([cp for cp in HANGUL if cp in cmap]) < len(HANGUL):
        sys.exit(f'{name}: not every Hangul syllable is in the font')


def load(spec, src):
    font = TTFont(src / spec['file'])
    if 'fvar' in font:
        font = instancer.instantiateVariableFont(font, {'wght': spec['wght']})
        buffer = io.BytesIO()
        font.save(buffer)
        buffer.seek(0)
        font = TTFont(buffer)
    return font


def woff2(data, cps):
    options = subset.Options()
    options.flavor = 'woff2'
    options.layout_features = ['*']
    options.name_IDs = ['*']
    options.notdef_outline = True
    sub = subset.Subsetter(options)
    sub.populate(unicodes=cps)
    copy = TTFont(io.BytesIO(data))
    sub.subset(copy)
    copy.flavor = 'woff2'
    buffer = io.BytesIO()
    copy.save(buffer)
    return buffer.getvalue()


def font_bytes(font):
    buffer = io.BytesIO()
    font.save(buffer)
    return buffer.getvalue()


def preview(data, out):
    pil = ImageFont.truetype(io.BytesIO(data), size=PREVIEW_HEIGHT * 3 // 4)
    left, top, right, bottom = pil.getbbox(PREVIEW_TEXT)
    image = Image.new('LA', (right - left + 8, PREVIEW_HEIGHT), (0, 0))
    ImageDraw.Draw(image).text((4 - left, (PREVIEW_HEIGHT - (bottom - top)) // 2 - top), PREVIEW_TEXT,
                               font=pil, fill=(0, 255))
    image.save(out, optimize=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--src', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent / 'src' / 'fonts'
    for d in ('files', 'previews'):
        shutil.rmtree(root / d, ignore_errors=True)
        (root / d).mkdir(parents=True)
    entries = []
    for spec in FONTS:
        font = load(spec, args.src)
        check(font, spec['id'])
        data = font_bytes(font)
        parts = []
        for n, cps in enumerate(s for s in slices(font) if s):
            name = f"{spec['id']}-{n}.woff2"
            (root / 'files' / name).write_bytes(woff2(data, cps))
            parts.append((name, runs(cps)))
        preview(data, root / 'previews' / f"{spec['id']}.png")
        entries.append((spec, parts))
        size = sum((root / 'files' / n).stat().st_size for n, _ in parts)
        print(f"{spec['id']}: {len(parts)} slices, {size // 1024} KB")
    lines = ['// Generated by scripts/build_fonts.py. Do not edit.', '',
             'export const FONT_MANIFEST = [']
    for spec, parts in entries:
        lines.append(f"  {{ id: '{spec['id']}', label: '{spec['label']}', slices: [")
        lines += [f"    {{ file: '{n}', range: '{r}' }}," for n, r in parts]
        lines.append('  ] },')
    lines += ['] as const;', '']
    (root / 'manifest.ts').write_text('\n'.join(lines), encoding='utf-8')


if __name__ == '__main__':
    main()
