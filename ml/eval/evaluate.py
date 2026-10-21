"""Runs the evaluation set (D-37) against an ML server and prints the D-39 metrics.

Usage:
    python evaluate.py --manifest /path/to/eval-set/manifest.csv [--server http://localhost:8000] [--out report.json]

manifest.csv has a header ``file,label``; ``file`` is relative to the manifest, ``label`` is ``ok`` or ``bad``.
The images stay out of git (S3, see ml/README.md). Uses the standard library only.
"""

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import asdict
from pathlib import Path

from metrics import Outcome, evaluate

# Spring gives the ML server 3 seconds (D-36); give a little more so slow answers still count in p95.
TIMEOUT_SECONDS = 10


def read_manifest(path: Path) -> list[tuple[Path, str]]:
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or not {"file", "label"} <= set(reader.fieldnames):
            raise SystemExit("manifest needs a header with 'file' and 'label'")
        return [(path.parent / row["file"], row["label"].strip().lower()) for row in reader]


def multipart(png: bytes, request_id: str) -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"requestId\"\r\n\r\n{request_id}\r\n".encode(),
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"d.png\"\r\n"
        "Content-Type: image/png\r\n\r\n".encode() + png + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def moderate(server: str, png: bytes, request_id: str) -> tuple[str | None, float]:
    body, content_type = multipart(png, request_id)
    request = urllib.request.Request(
        f"{server.rstrip('/')}/v1/moderate", data=body, headers={"Content-Type": content_type}, method="POST"
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as res:
            label = json.load(res)["image"]["label"]
    except (urllib.error.URLError, TimeoutError, KeyError, ValueError):
        label = None
    return label, (time.perf_counter() - started) * 1000


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--server", default="http://localhost:8000")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)

    outcomes = []
    for index, (file, truth) in enumerate(read_manifest(args.manifest)):
        label, latency = moderate(args.server, file.read_bytes(), f"eval-{index}")
        outcomes.append(Outcome(truth, label, latency if label is not None else None))

    report = evaluate(outcomes)
    result = {"report": asdict(report), "checks": report.checks, "passed": report.passed}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("Not checked here: at least one inappropriate doodle that OpenAI passed (D-39, production data).")
    if args.out:
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
