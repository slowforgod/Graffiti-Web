"""Measures OCR on handwriting and picks ``[ocr] min_confidence`` for models.lock (07b decision 3).

Usage:
    python ocr_eval.py --model-dir <folder with the OCR weights> --manifest <set>/manifest.csv [--out report.json]
    python ocr_eval.py --model-dir <folder> --aihub-json <labels.json> --images <png folder> [--limit 400]

``manifest.csv`` has a header ``file,text`` and an optional ``category`` column (a docs/FILTER.md name for
samples that are inappropriate on purpose, empty for normal text). ``--aihub-json`` reads the AI Hub
"한글 손글씨" label file instead and uses only the text of the images found in ``--images``; it never reads the
writers' attributes. Images are composited onto white and shrunk to the server's 1024 px limit first.

Runs EasyOCR in this process with the ml/serving code, so it needs the ml/serving requirements. Prints numbers
only: never the recognised text, which may hold the inappropriate samples or personal information.
"""

import argparse
import csv
import json
import sys
import time
from pathlib import Path

from PIL import Image

from ocr_metrics import ReadResult, choose_min_confidence, summarize

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "serving"))
from app.image_input import on_white  # noqa: E402
from app.lock import OcrModelLock  # noqa: E402
from app.settings import DEFAULT_MAX_IMAGE_SIDE  # noqa: E402

Sample = tuple[Path, str, str | None]


def read_manifest(path: Path) -> list[Sample]:
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or not {"file", "text"} <= set(reader.fieldnames):
            raise SystemExit("manifest needs a header with 'file' and 'text'")
        return [(path.parent / r["file"], r["text"], (r.get("category") or "").strip() or None) for r in reader]


def read_aihub(labels: Path, images: Path) -> list[Sample]:
    data = json.loads(labels.read_text(encoding="utf-8"))
    texts = {a["image_id"]: a["text"] for a in data["annotations"]}
    files = sorted(p for p in images.rglob("*.png") if p.stem in texts)
    return [(p, texts[p.stem], None) for p in files]


def prepare(path: Path) -> Image.Image:
    image = on_white(Image.open(path))
    scale = DEFAULT_MAX_IMAGE_SIDE / max(image.size)
    if scale < 1:
        image = image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))))
    return image


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--aihub-json", type=Path)
    parser.add_argument("--images", type=Path)
    parser.add_argument("--langs", default="ko,en")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)

    if args.manifest:
        samples = read_manifest(args.manifest)
    elif args.aihub_json and args.images:
        samples = read_aihub(args.aihub_json, args.images)
    else:
        raise SystemExit("give --manifest, or --aihub-json with --images")
    samples = samples[: args.limit] if args.limit else samples

    from app.ocr import EasyOcrEngine

    engine = EasyOcrEngine(args.model_dir, OcrModelLock("eval", "easyocr", tuple(args.langs.split(","))), args.threads)
    results = []
    for path, truth, category in samples:
        image = prepare(path)
        started = time.perf_counter()
        chunks = engine.chunks(image)
        latency = (time.perf_counter() - started) * 1000
        results.append(ReadResult(truth, [(text, conf) for _, text, conf in chunks], latency, category))

    normal = [conf for r in results if r.category is None for _, conf in r.chunks]
    threshold = choose_min_confidence(normal)
    text = json.dumps(summarize(results, threshold), ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
