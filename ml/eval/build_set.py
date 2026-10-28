"""Builds an evaluation set for the image model (D-37 as amended 2026-10-28, ml-zeroshot decision 10).

Usage:
    python build_set.py --out sets/<name> [--quickdraw cat.ndjson ...] [--per-file 200]
                        [--hate-variants 300] [--team <folder>] [--seed 0]

Sources:
- Normal doodles: Quick, Draw! "simplified" ndjson files (Google, CC BY 4.0), redrawn the way our canvas
  draws: white background, one of the app's stroke widths and palette colours, at most 1024 px. Only
  drawings the game recognised are used. Each drawing counts as its own drawer.
- Hate symbols: swastikas generated in code, rotated, scaled and with shaky lines. Only the clockwise
  form (卐) is generated, never the Buddhist manji (卍), which is common in Korea and is not a hate symbol.
- Team doodles: ``<folder>/<CATEGORY>/<drawer>/*.png``, CATEGORY being a docs/FILTER.md name.

Writes ``images/<n>.png`` and ``manifest.csv`` (file,label,category,drawer) for calibrate.py. The set stays out
of git (S3 and ml/eval/sets/, see ml/README.md). Needs Pillow, which the ml/serving requirements install.
"""

import argparse
import csv
import hashlib
import io
import json
import math
import random
import sys
from collections.abc import Iterable, Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw

from metrics import BAD, OK

# Same as graffiti.doodle in backend application.yml and the 1024 px bbox limit (API.md section 3).
STROKE_WIDTHS = (2, 4, 8, 16)
INK = ("#000000", "#E53935", "#FB8C00", "#43A047", "#1E88E5", "#8E24AA", "#EC407A", "#795548", "#9E9E9E")
MAX_SIDE = 1024
MIN_SIDE = 96
# Share of doodles drawn in black; the rest use another palette colour.
BLACK_SHARE = 0.6
# Generated variants come from one generator and look alike, so this many share a drawer.
VARIANTS_PER_DRAWER = 10
# Points added along each straight segment so the line can wobble.
WOBBLE_POINTS = 8
MAX_WOBBLE = 0.025
FRAME_SHARE = 0.3

Point = tuple[float, float]
Stroke = list[Point]


@dataclass(frozen=True)
class Entry:
    png: bytes
    label: str
    category: str | None
    drawer: str


def render(strokes: Sequence[Stroke], seed: int) -> bytes:
    """Draws strokes, in any coordinates, as a PNG cropped to the doodle like the server renders it."""
    rng = random.Random(seed)
    width = rng.choice(STROKE_WIDTHS)
    ink = INK[0] if rng.random() < BLACK_SHARE else rng.choice(INK[1:])
    side = rng.randint(MIN_SIDE, MAX_SIDE)

    xs = [x for stroke in strokes for x, _ in stroke]
    ys = [y for stroke in strokes for _, y in stroke]
    span = max(max(xs) - min(xs), max(ys) - min(ys)) or 1.0
    scale = (side - width) / span
    size = (
        max(1, min(MAX_SIDE, math.ceil((max(xs) - min(xs)) * scale) + width)),
        max(1, min(MAX_SIDE, math.ceil((max(ys) - min(ys)) * scale) + width)),
    )
    image = Image.new("RGB", size, "white")
    draw = ImageDraw.Draw(image)
    radius = width / 2
    for stroke in strokes:
        points = [((x - min(xs)) * scale + radius, (y - min(ys)) * scale + radius) for x, y in stroke]
        if len(points) > 1:
            draw.line(points, fill=ink, width=width, joint="curve")
        for x, y in (points[0], points[-1]):
            draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=ink)
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


def quickdraw_entries(paths: Iterable[Path], per_file: int) -> Iterator[Entry]:
    for path in paths:
        kept = 0
        with path.open(encoding="utf-8") as f:
            for line in f:
                if kept >= per_file:
                    break
                row = json.loads(line)
                if not row.get("recognized"):
                    continue
                strokes = [list(zip(xs, ys)) for xs, ys, *_ in row["drawing"]]
                key = str(row["key_id"])
                seed = int(hashlib.sha256(key.encode()).hexdigest()[:8], 16)
                yield Entry(render(strokes, seed=seed), OK, None, f"qd-{key}")
                kept += 1


def swastika() -> list[Stroke]:
    """The four hooked arms in image coordinates (y down), turning clockwise: the top arm hooks right."""
    return [
        [(0.0, 0.0), (0.0, -1.0), (1.0, -1.0)],
        [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)],
        [(0.0, 0.0), (0.0, 1.0), (-1.0, 1.0)],
        [(0.0, 0.0), (-1.0, 0.0), (-1.0, -1.0)],
    ]


def hate_symbol_entries(count: int, seed: int) -> Iterator[Entry]:
    rng = random.Random(seed)
    for index in range(count):
        strokes = swastika()
        if rng.random() < FRAME_SHARE:
            strokes.append(_circle(1.6))
        angle = math.radians(rng.uniform(0, 360))
        wobble = rng.uniform(0, MAX_WOBBLE)
        strokes = [_wobble(_rotate(stroke, angle), wobble, rng) for stroke in strokes]
        drawer = f"syn-hate-{index // VARIANTS_PER_DRAWER}"
        yield Entry(render(strokes, seed=rng.randrange(1 << 30)), BAD, "HATE", drawer)


def team_entries(root: Path) -> Iterator[Entry]:
    for path in sorted(root.glob("*/*/*.png")):
        yield Entry(path.read_bytes(), BAD, path.parent.parent.name, f"team-{path.parent.name}")


def write_set(entries: Iterable[Entry], out: Path) -> dict[str, int]:
    images = out / "images"
    images.mkdir(parents=True, exist_ok=True)
    counts = {OK: 0, BAD: 0}
    with (out / "manifest.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["file", "label", "category", "drawer"])
        for index, entry in enumerate(entries):
            name = f"images/{index}.png"
            (out / name).write_bytes(entry.png)
            writer.writerow([name, entry.label, entry.category or "", entry.drawer])
            counts[entry.label] += 1
    return counts


def _rotate(stroke: Stroke, angle: float) -> Stroke:
    cos, sin = math.cos(angle), math.sin(angle)
    return [(x * cos - y * sin, x * sin + y * cos) for x, y in stroke]


def _wobble(stroke: Stroke, amount: float, rng: random.Random) -> Stroke:
    points = [stroke[0]]
    for (x0, y0), (x1, y1) in zip(stroke, stroke[1:]):
        for step in range(1, WOBBLE_POINTS + 1):
            t = step / WOBBLE_POINTS
            points.append((x0 + (x1 - x0) * t + rng.gauss(0, amount), y0 + (y1 - y0) * t + rng.gauss(0, amount)))
    return points


def _circle(radius: float) -> Stroke:
    steps = 48
    angles = [2 * math.pi * i / steps for i in range(steps + 1)]
    return [(radius * math.cos(a), radius * math.sin(a)) for a in angles]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--quickdraw", type=Path, nargs="*", default=[])
    parser.add_argument("--per-file", type=int, default=200)
    parser.add_argument("--hate-variants", type=int, default=0)
    parser.add_argument("--team", type=Path)
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args(argv)

    def entries() -> Iterator[Entry]:
        yield from quickdraw_entries(args.quickdraw, args.per_file)
        yield from hate_symbol_entries(args.hate_variants, args.seed)
        if args.team:
            yield from team_entries(args.team)

    counts = write_set(entries(), args.out)
    print(f"ok={counts[OK]} bad={counts[BAD]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
