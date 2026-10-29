"""Picks the per-category thresholds for a zero-shot model version and reports them against D-39.

Usage:
    python calibrate.py --manifest sets/<set>/manifest.csv [--server http://localhost:8000] [--out report.json]

manifest.csv has a header ``file,label,category,drawer`` (build_set.py writes it). ``label`` is ``ok`` or
``bad``; ``category`` names the FILTER.md category of a bad doodle; ``drawer`` is an anonymous ID of the
person who drew it. The server's own thresholds are ignored: only its scores are used.

Rule (docs/specs/ml-zeroshot.md decision 5): on the calibration part, each category gets the lowest threshold
that flags at most 2% / (number of categories) of the normal doodles. The report part then gives the D-39
numbers for those thresholds. Prints numbers only, never images or file names of bad doodles.
"""

import argparse
import csv
import json
import sys
from dataclasses import asdict
from pathlib import Path

from calibration import Sample, fit_threshold, label_for, per_category_budget, split_by_drawer
from evaluate import moderate_image
from metrics import BAD, OK, Outcome, evaluate

COLUMNS = ("file", "label", "category", "drawer")


def read_samples(path: Path) -> list[Sample]:
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or not set(COLUMNS) <= set(reader.fieldnames):
            raise SystemExit(f"manifest needs the columns {', '.join(COLUMNS)} (drawer keeps the split honest)")
        return [
            Sample(row["file"], row["label"].strip().lower(), row["category"].strip() or None, row["drawer"].strip())
            for row in reader
        ]


def calibrate(samples: list[Sample], scored: dict[str, tuple[dict | None, float]]) -> dict:
    """``scored`` maps a sample's file to the server's scores (None on failure) and the latency in ms."""
    calibration, report = split_by_drawer(samples)
    ok_scores = [scored[s.file][0] for s in calibration if s.label == OK and scored[s.file][0] is not None]
    if not ok_scores:
        raise SystemExit("the calibration part has no scored normal doodles")
    categories = sorted(ok_scores[0])
    budget = per_category_budget(len(categories))
    thresholds = {c: fit_threshold([scores[c] for scores in ok_scores], budget) for c in categories}

    outcomes = []
    caught: dict[str, list[bool]] = {}
    for sample in report:
        scores, latency = scored[sample.file]
        label = None if scores is None else label_for(scores, thresholds)
        outcomes.append(Outcome(sample.label, label, latency if label is not None else None))
        if sample.label == BAD and sample.category in thresholds and label is not None:
            hit = scores.get(sample.category, 0.0) >= thresholds[sample.category]
            caught.setdefault(sample.category, []).append(hit)
    result = evaluate(outcomes)
    return {
        "thresholds": thresholds,
        "budget_per_category": budget,
        "calibration_samples": len(calibration),
        "report": asdict(result),
        "recall_by_category": {c: sum(v) / len(v) for c, v in sorted(caught.items())},
        "checks": result.checks,
        "passed": result.passed,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--server", default="http://localhost:8000")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)

    samples = read_samples(args.manifest)
    scored = {}
    for index, sample in enumerate(samples):
        image, latency = moderate_image(args.server, (args.manifest.parent / sample.file).read_bytes(), f"cal-{index}")
        scored[sample.file] = ((image or {}).get("scores"), latency)

    result = calibrate(samples, scored)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
