import pytest

from ocr_metrics import KEEP_SHARE, ReadResult, char_error_rate, choose_min_confidence, gated_text, summarize


def test_character_error_rate_ignores_spaces():
    assert char_error_rate("안녕 하세요", "안녕하세요") == 0.0
    assert char_error_rate("안넝하세요", "안녕하세요") == pytest.approx(1 / 5)
    assert char_error_rate("", "안녕") == 1.0
    assert char_error_rate("안녕", "") == 0.0


def test_min_confidence_keeps_the_agreed_share_of_normal_chunks():
    confidences = [i / 100 for i in range(1, 101)]  # 0.01 .. 1.00

    threshold = choose_min_confidence(confidences)

    kept = sum(c >= threshold for c in confidences) / len(confidences)
    assert kept == pytest.approx(KEEP_SHARE)
    assert choose_min_confidence(confidences, keep_share=1.0) == 0.01


def test_min_confidence_needs_chunks():
    with pytest.raises(ValueError):
        choose_min_confidence([])


def test_gated_text_joins_confident_chunks_in_order():
    chunks = [("안녕", 0.9), ("xx", 0.1), ("하세요", 0.5)]
    assert gated_text(chunks, 0.4) == "안녕 하세요"


def test_summary_reports_error_rates_before_and_after_the_gate():
    results = [
        ReadResult("안녕하세요", [("안녕하세요", 0.8)], 100.0, None),
        ReadResult("반갑습니다", [("반갑", 0.2), ("습니다", 0.9)], 300.0, None),
        ReadResult("씨발", [("씨발", 0.7)], 200.0, "PROFANITY"),
    ]

    summary = summarize(results, min_confidence=0.5)

    assert summary["samples"] == 3
    assert summary["cer_all_chunks"] == pytest.approx(0.0)
    assert summary["cer_after_gate"] == pytest.approx((0 + 2 / 5 + 0) / 3)
    assert summary["chunks_kept"] == pytest.approx(3 / 4)
    assert summary["by_category"] == {"PROFANITY": {"samples": 1, "cer_after_gate": 0.0}}
    assert summary["p95_latency_ms"] == 300.0
