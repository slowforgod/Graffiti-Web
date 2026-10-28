"""Threshold calibration for the zero-shot model (docs/specs/ml-zeroshot.md decisions 5 and 6). Pure logic.

The evaluation set is split by drawer into a calibration part, which picks the thresholds, and a report part,
which measures them against D-39. One drawer's doodles look alike, so letting them land on both sides would
make the report look better than the model is.
"""

import hashlib
import math
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass

from metrics import BAD, FLAGGED, MAX_FALSE_POSITIVE_RATE, OK, SAFE

CALIBRATION_SHARE = 0.6


class SplitError(ValueError):
    """The set cannot be split so that both sides have doodles."""


@dataclass(frozen=True)
class Sample:
    """One manifest row. ``category`` names the FILTER.md category of a bad doodle when known."""

    file: str
    label: str
    category: str | None
    drawer: str

    def __post_init__(self):
        if self.label not in (OK, BAD):
            raise ValueError(f"label must be {OK!r} or {BAD!r}, not {self.label!r}")
        if not self.drawer:
            raise ValueError("every sample needs a drawer")


def split_by_drawer(samples: Iterable[Sample]) -> tuple[list[Sample], list[Sample]]:
    """Calibration and report samples. A drawer's side depends only on its ID, so reruns give the same split."""
    calibration, report = [], []
    for sample in samples:
        (calibration if _bucket(sample.drawer) < CALIBRATION_SHARE else report).append(sample)
    if not calibration or not report:
        raise SplitError("need doodles from more drawers: one side of the split is empty")
    return calibration, report


def per_category_budget(categories: int) -> float:
    """Each category may flag at most this share of normal doodles, so together they stay within D-39."""
    return MAX_FALSE_POSITIVE_RATE / categories


def fit_threshold(ok_scores: Sequence[float], budget: float) -> float:
    """The lowest threshold at which at most ``budget`` of the normal doodles score at or above it."""
    if not ok_scores:
        raise ValueError("need normal doodles to pick a threshold")
    ordered = sorted(ok_scores, reverse=True)
    # The small epsilon keeps 0.02 * 100 from rounding down to 1.
    allowed = math.floor(budget * len(ordered) + 1e-9)
    if allowed >= len(ordered):
        return 0.0
    # Just above the first score that must not be flagged; ties at that score stay unflagged too.
    return min(1.0, math.nextafter(ordered[allowed], math.inf))


def label_for(scores: Mapping[str, float], thresholds: Mapping[str, float]) -> str:
    """Same rule as the server (ml/serving/app/decision.py)."""
    return FLAGGED if any(scores.get(c, 0.0) >= t for c, t in thresholds.items()) else SAFE


def _bucket(drawer: str) -> float:
    return int(hashlib.sha256(drawer.encode()).hexdigest()[:8], 16) / 2**32
