"""D-39 metrics for the image model. Pure functions, no I/O."""

import math
from collections.abc import Iterable
from dataclasses import dataclass

OK = "ok"     # a normal doodle
BAD = "bad"   # an inappropriate doodle
FLAGGED = "FLAGGED"
SAFE = "SAFE"

# D-39 criteria for leaving shadow mode.
MAX_FALSE_POSITIVE_RATE = 0.02
MIN_RECALL = 0.70
MAX_P95_LATENCY_MS = 3000
MIN_OK_SAMPLES = 500
MIN_BAD_SAMPLES = 30


@dataclass(frozen=True)
class Outcome:
    """One evaluated image. ``predicted`` is None when the server failed or timed out."""

    truth: str
    predicted: str | None
    latency_ms: float | None


@dataclass(frozen=True)
class Report:
    ok_samples: int
    bad_samples: int
    errors: int
    false_positive_rate: float | None
    recall: float | None
    p95_latency_ms: float | None

    @property
    def checks(self) -> dict[str, bool]:
        return {
            "false_positive_rate<=2%": self.false_positive_rate is not None
            and self.false_positive_rate <= MAX_FALSE_POSITIVE_RATE,
            "recall>=70%": self.recall is not None and self.recall >= MIN_RECALL,
            "p95<=3s": self.p95_latency_ms is not None and self.p95_latency_ms <= MAX_P95_LATENCY_MS,
            "samples(ok>=500,bad>=30)": self.ok_samples >= MIN_OK_SAMPLES and self.bad_samples >= MIN_BAD_SAMPLES,
        }

    @property
    def passed(self) -> bool:
        return all(self.checks.values())


def percentile(values: list[float], pct: float) -> float | None:
    """Nearest-rank percentile; None for no values."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(pct / 100 * len(ordered)))
    return ordered[rank - 1]


def evaluate(outcomes: Iterable[Outcome]) -> Report:
    outcomes = list(outcomes)
    for o in outcomes:
        if o.truth not in (OK, BAD):
            raise ValueError(f"unknown truth label: {o.truth!r}")
    answered = [o for o in outcomes if o.predicted is not None]
    ok = [o for o in answered if o.truth == OK]
    bad = [o for o in answered if o.truth == BAD]
    false_positives = sum(1 for o in ok if o.predicted == FLAGGED)
    caught = sum(1 for o in bad if o.predicted == FLAGGED)
    return Report(
        ok_samples=len(ok),
        bad_samples=len(bad),
        errors=len(outcomes) - len(answered),
        false_positive_rate=false_positives / len(ok) if ok else None,
        recall=caught / len(bad) if bad else None,
        p95_latency_ms=percentile([o.latency_ms for o in answered if o.latency_ms is not None], 95),
    )
