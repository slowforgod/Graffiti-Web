from app.decision import FLAGGED, SAFE, decide

THRESHOLDS = {"sexual": 0.5, "violence": 0.6}


def test_flags_when_a_category_reaches_its_threshold():
    assert decide({"sexual": 0.5, "violence": 0.1, "safe": 0.4}, THRESHOLDS) == FLAGGED
    assert decide({"sexual": 0.1, "violence": 0.61}, THRESHOLDS) == FLAGGED


def test_safe_when_every_category_is_below_its_threshold():
    assert decide({"sexual": 0.49, "violence": 0.59, "safe": 0.9}, THRESHOLDS) == SAFE


def test_safe_score_and_unthresholded_categories_never_flag():
    assert decide({"safe": 1.0, "other": 0.99}, THRESHOLDS) == SAFE


def test_missing_scores_count_as_zero():
    assert decide({}, THRESHOLDS) == SAFE
