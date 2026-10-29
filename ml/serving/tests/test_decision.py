from app.decision import FLAGGED, SAFE, decide

THRESHOLDS = {"SEXUAL": 0.5, "HATE": 0.6}


def test_flags_when_a_category_reaches_its_threshold():
    assert decide({"SEXUAL": 0.5, "HATE": 0.1}, THRESHOLDS) == FLAGGED
    assert decide({"SEXUAL": 0.1, "HATE": 0.61}, THRESHOLDS) == FLAGGED


def test_safe_when_every_category_is_below_its_threshold():
    assert decide({"SEXUAL": 0.49, "HATE": 0.59}, THRESHOLDS) == SAFE


def test_unthresholded_categories_never_flag():
    assert decide({"other": 0.99}, THRESHOLDS) == SAFE


def test_missing_scores_count_as_zero():
    assert decide({}, THRESHOLDS) == SAFE
