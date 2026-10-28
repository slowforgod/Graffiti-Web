"""Metrics for picking the OCR confidence gate (docs/specs/07b-ml-moderation.md decision 3). Pure functions."""

import math
from collections.abc import Sequence
from dataclasses import dataclass

from metrics import percentile

# Decision 3: the gate starts where this share of normal handwriting chunks still gets through.
KEEP_SHARE = 0.9


@dataclass(frozen=True)
class ReadResult:
    """One handwriting image: the true text, the chunks OCR read in reading order, the time, and the
    FILTER.md category when the sample is inappropriate on purpose (None for normal text)."""

    truth: str
    chunks: list[tuple[str, float]]
    latency_ms: float
    category: str | None


def char_error_rate(predicted: str, truth: str) -> float:
    """Edit distance over the truth's length, both without spaces: spacing mistakes do not change a check."""
    p, t = predicted.replace(" ", ""), truth.replace(" ", "")
    if not t:
        return 0.0
    return min(1.0, _edit_distance(p, t) / len(t))


def choose_min_confidence(confidences: Sequence[float], keep_share: float = KEEP_SHARE) -> float:
    """The highest threshold that still keeps ``keep_share`` of the given chunks."""
    if not confidences:
        raise ValueError("need OCR chunks to pick a threshold")
    ordered = sorted(confidences)
    dropped = math.floor((1 - keep_share) * len(ordered) + 1e-9)
    return ordered[min(dropped, len(ordered) - 1)]


def gated_text(chunks: Sequence[tuple[str, float]], min_confidence: float) -> str:
    """Same rule as the server (ml/serving/app/ocr.py gate), on chunks already in reading order."""
    return " ".join(text.strip() for text, conf in chunks if conf >= min_confidence and text.strip())


def summarize(results: Sequence[ReadResult], min_confidence: float) -> dict:
    chunks = [conf for r in results for _, conf in r.chunks]
    after = [char_error_rate(gated_text(r.chunks, min_confidence), r.truth) for r in results]
    by_category: dict[str, list[float]] = {}
    for r, cer in zip(results, after):
        if r.category:
            by_category.setdefault(r.category, []).append(cer)
    return {
        "samples": len(results),
        "min_confidence": min_confidence,
        "cer_all_chunks": _mean([char_error_rate(gated_text(r.chunks, 0.0), r.truth) for r in results]),
        "cer_after_gate": _mean(after),
        "chunks_kept": sum(c >= min_confidence for c in chunks) / len(chunks) if chunks else None,
        "confidence_p10_p50_p90": [percentile(chunks, p) for p in (10, 50, 90)],
        "by_category": {c: {"samples": len(v), "cer_after_gate": _mean(v)} for c, v in sorted(by_category.items())},
        "p95_latency_ms": percentile([r.latency_ms for r in results], 95),
    }


def _mean(values: Sequence[float]) -> float | None:
    return sum(values) / len(values) if values else None


def _edit_distance(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]
