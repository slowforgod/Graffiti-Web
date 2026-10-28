import pytest

from calibration import (
    CALIBRATION_SHARE,
    Sample,
    SplitError,
    fit_threshold,
    label_for,
    split_by_drawer,
)
from metrics import BAD, FLAGGED, OK, SAFE


def samples(count, drawers):
    return [Sample(f"{i}.png", OK, None, f"d{i % drawers}") for i in range(count)]


def test_split_keeps_each_drawer_on_one_side():
    calibration, report = split_by_drawer(samples(600, 200))

    assert {s.drawer for s in calibration}.isdisjoint({s.drawer for s in report})
    assert len(calibration) + len(report) == 600


def test_split_is_about_sixty_forty_and_repeatable():
    calibration, report = split_by_drawer(samples(2000, 2000))

    assert len(calibration) / 2000 == pytest.approx(CALIBRATION_SHARE, abs=0.05)
    assert split_by_drawer(samples(2000, 2000)) == (calibration, report)


def test_split_refuses_a_side_left_empty():
    with pytest.raises(SplitError):
        split_by_drawer(samples(10, 1))


@pytest.mark.parametrize("budget, allowed", [(0.02, 2), (0.01, 1), (0.0, 0)])
def test_threshold_is_the_lowest_that_keeps_false_positives_within_budget(budget, allowed):
    ok_scores = [i / 100 for i in range(100)]  # 0.00 .. 0.99

    threshold = fit_threshold(ok_scores, budget)

    assert sum(s >= threshold for s in ok_scores) == allowed
    # Any lower threshold lets one more normal doodle through as flagged.
    lower = max(s for s in ok_scores if s < threshold)
    assert sum(s >= lower for s in ok_scores) > allowed


def test_tied_scores_never_push_false_positives_over_budget():
    ok_scores = [0.9] * 5 + [0.1] * 95

    threshold = fit_threshold(ok_scores, 0.02)

    assert sum(s >= threshold for s in ok_scores) <= 2
    assert threshold <= 1.0


def test_threshold_needs_normal_samples():
    with pytest.raises(ValueError):
        fit_threshold([], 0.02)


def test_label_flags_when_any_category_reaches_its_threshold():
    thresholds = {"SEXUAL": 0.7, "HATE": 0.8}
    assert label_for({"SEXUAL": 0.7, "HATE": 0.1}, thresholds) == FLAGGED
    assert label_for({"SEXUAL": 0.69, "HATE": 0.79}, thresholds) == SAFE


def test_sample_rejects_unknown_labels():
    with pytest.raises(ValueError):
        Sample("a.png", "maybe", None, "d1")
    assert Sample("a.png", BAD, "HATE", "d1").category == "HATE"


def test_budget_is_split_across_categories():
    from calibration import per_category_budget

    assert per_category_budget(2) == pytest.approx(0.01)
    assert per_category_budget(3) * 3 == pytest.approx(0.02)
