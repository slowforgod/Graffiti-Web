"""Handwriting OCR for drawn doodles (D-35, docs/specs/07b-ml-moderation.md).

EasyOCR finds text chunks with a confidence each. Only chunks at or above ``min_confidence`` in models.lock
are kept, so a scribble that merely looks like letters does not reach the text checks. There is no minimum
text length (2026-10-28 decision). The gate and the reading order are pure functions.
"""

import statistics
import tempfile
from collections.abc import Sequence
from pathlib import Path

from PIL import Image

from .lock import OcrModelLock

# (four corner points, text, confidence) as EasyOCR's readtext returns them.
Chunk = tuple[Sequence[Sequence[float]], str, float]


def gate(chunks: Sequence[Chunk], min_confidence: float) -> tuple[str, float]:
    """The kept chunks' text in reading order, joined by spaces, and their mean confidence."""
    kept = [c for c in reading_order(chunks) if c[2] >= min_confidence and c[1].strip()]
    if not kept:
        return "", 0.0
    return " ".join(c[1].strip() for c in kept), float(statistics.fmean(c[2] for c in kept))


def reading_order(chunks: Sequence[Chunk]) -> list[Chunk]:
    """Top to bottom, then left to right. Chunks whose vertical centres are within half a line share a line."""
    if not chunks:
        return []
    half_line = statistics.median(_bottom(c) - _top(c) for c in chunks) / 2
    lines: list[list[Chunk]] = []
    for chunk in sorted(chunks, key=_centre):
        if lines and _centre(chunk) - _centre(lines[-1][0]) <= half_line:
            lines[-1].append(chunk)
        else:
            lines.append([chunk])
    return [c for line in lines for c in sorted(line, key=_left)]


class EasyOcrEngine:
    """EasyOCR on CPU with the files from models.lock only; it never downloads (SEC-17)."""

    def __init__(self, model_dir: Path, lock: OcrModelLock, threads: int):
        import easyocr
        import torch

        torch.set_num_threads(threads)
        # EasyOCR adds this folder to sys.path for custom networks; an empty private folder keeps that inert.
        self._reader = easyocr.Reader(
            list(lock.langs),
            gpu=False,
            model_storage_directory=str(model_dir),
            user_network_directory=tempfile.mkdtemp(prefix="easyocr-"),
            download_enabled=False,
            verbose=False,
        )
        self._min_confidence = lock.min_confidence
        self.version = lock.version

    def read(self, image: Image.Image) -> tuple[str, float]:
        return gate(self.chunks(image), self._min_confidence)

    def chunks(self, image: Image.Image) -> list[Chunk]:
        """Every chunk EasyOCR found, in reading order, before the confidence gate (ml/eval/ocr_eval.py)."""
        import numpy as np

        found = self._reader.readtext(np.asarray(image.convert("RGB")))
        return reading_order([(box, text, float(conf)) for box, text, conf in found])


def _top(chunk: Chunk) -> float:
    return min(p[1] for p in chunk[0])


def _bottom(chunk: Chunk) -> float:
    return max(p[1] for p in chunk[0])


def _centre(chunk: Chunk) -> float:
    return (_top(chunk) + _bottom(chunk)) / 2


def _left(chunk: Chunk) -> float:
    return min(p[0] for p in chunk[0])
