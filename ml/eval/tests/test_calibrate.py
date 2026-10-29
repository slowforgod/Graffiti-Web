import pytest

from calibrate import calibrate, read_samples
from calibration import Sample, split_by_drawer
from metrics import BAD, OK


def build_set():
    """1000 normal doodles from 1000 drawers and 100 bad ones from 50 drawers, with scores that separate."""
    samples, scored = [], {}
    for i in range(1000):
        sample = Sample(f"ok-{i}.png", OK, None, f"qd-{i}")
        samples.append(sample)
        scored[sample.file] = ({"SEXUAL": i / 1000, "HATE": (999 - i) / 1000}, 120.0)
    for i in range(100):
        category = "SEXUAL" if i % 2 else "HATE"
        sample = Sample(f"bad-{i}.png", BAD, category, f"team-{i % 50}")
        samples.append(sample)
        scores = {c: 0.995 if c == category else 0.2 for c in ("SEXUAL", "HATE")}
        scored[sample.file] = (scores, 150.0)
    return samples, scored


def test_thresholds_keep_each_category_within_its_share_of_false_positives():
    samples, scored = build_set()
    result = calibrate(samples, scored)

    calibration, _ = split_by_drawer(samples)
    ok = [scored[s.file][0] for s in calibration if s.label == OK]
    for category, threshold in result["thresholds"].items():
        flagged = sum(scores[category] >= threshold for scores in ok)
        assert flagged / len(ok) <= 0.01
    assert result["budget_per_category"] == pytest.approx(0.01)


def test_report_uses_only_the_other_drawers():
    samples, scored = build_set()
    result = calibrate(samples, scored)

    _, report = split_by_drawer(samples)
    assert result["report"]["ok_samples"] + result["report"]["bad_samples"] == len(report)
    assert result["calibration_samples"] == len(samples) - len(report)
    assert result["recall_by_category"] == {"HATE": 1.0, "SEXUAL": 1.0}
    assert result["report"]["recall"] == 1.0
    assert result["checks"]["p95<=3s"]


def test_failed_answers_count_as_errors_not_as_safe():
    samples, scored = build_set()
    _, report = split_by_drawer(samples)
    broken = next(s for s in report if s.label == BAD)
    scored[broken.file] = (None, 3000.0)

    assert calibrate(samples, scored)["report"]["errors"] == 1


def test_manifest_without_drawer_is_refused(tmp_path):
    manifest = tmp_path / "manifest.csv"
    manifest.write_text("file,label\na.png,ok\n", encoding="utf-8")
    with pytest.raises(SystemExit, match="drawer"):
        read_samples(manifest)


def test_manifest_rows_become_samples(tmp_path):
    manifest = tmp_path / "manifest.csv"
    manifest.write_text("file,label,category,drawer\na.png,ok,,qd-1\nb.png,BAD,HATE,team-1\n", encoding="utf-8")
    assert read_samples(manifest) == [Sample("a.png", OK, None, "qd-1"), Sample("b.png", BAD, "HATE", "team-1")]
