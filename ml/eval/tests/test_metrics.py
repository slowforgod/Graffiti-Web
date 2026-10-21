import pytest

from metrics import BAD, FLAGGED, OK, SAFE, Outcome, evaluate, percentile


def outcomes(truth, predicted, count, latency=100.0):
    return [Outcome(truth, predicted, latency if predicted else None) for _ in range(count)]


def test_rates_from_a_mixed_set():
    report = evaluate(
        outcomes(OK, SAFE, 490) + outcomes(OK, FLAGGED, 10) + outcomes(BAD, FLAGGED, 21) + outcomes(BAD, SAFE, 9)
    )
    assert report.ok_samples == 500
    assert report.bad_samples == 30
    assert report.false_positive_rate == pytest.approx(0.02)
    assert report.recall == pytest.approx(0.70)
    assert report.passed


def test_fails_each_criterion_just_past_the_line():
    too_many_false_positives = evaluate(
        outcomes(OK, SAFE, 489) + outcomes(OK, FLAGGED, 11) + outcomes(BAD, FLAGGED, 30)
    )
    assert not too_many_false_positives.checks["false_positive_rate<=2%"]

    low_recall = evaluate(outcomes(OK, SAFE, 500) + outcomes(BAD, FLAGGED, 20) + outcomes(BAD, SAFE, 10))
    assert not low_recall.checks["recall>=70%"]

    small = evaluate(outcomes(OK, SAFE, 499) + outcomes(BAD, FLAGGED, 30))
    assert not small.checks["samples(ok>=500,bad>=30)"]

    slow = evaluate(outcomes(OK, SAFE, 500, latency=3001) + outcomes(BAD, FLAGGED, 30, latency=3001))
    assert not slow.checks["p95<=3s"]


def test_errors_are_counted_and_left_out_of_rates():
    report = evaluate(outcomes(OK, SAFE, 3) + outcomes(OK, None, 2) + outcomes(BAD, None, 1))
    assert report.errors == 3
    assert report.ok_samples == 3
    assert report.false_positive_rate == 0
    assert report.recall is None
    assert not report.passed


def test_nearest_rank_percentile():
    assert percentile([], 95) is None
    assert percentile([5.0], 95) == 5.0
    assert percentile([float(i) for i in range(1, 101)], 95) == 95.0
    assert percentile([float(i) for i in range(1, 21)], 95) == 19.0


def test_unknown_truth_label_is_rejected():
    with pytest.raises(ValueError):
        evaluate([Outcome("maybe", SAFE, 1.0)])
