"""Turns category scores into the image label (API.md section 7). Pure logic."""

from collections.abc import Mapping

SAFE = "SAFE"
FLAGGED = "FLAGGED"


def decide(scores: Mapping[str, float], thresholds: Mapping[str, float]) -> str:
    """FLAGGED when any thresholded category scores at or above its threshold.

    Categories without a threshold never flag.
    """
    for category, threshold in thresholds.items():
        if scores.get(category, 0.0) >= threshold:
            return FLAGGED
    return SAFE
