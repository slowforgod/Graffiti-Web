import pytest

from app.ocr import gate, reading_order


def box(left, top, width=40, height=20):
    return [[left, top], [left + width, top], [left + width, top + height], [left, top + height]]


def test_gate_keeps_chunks_at_or_above_the_minimum_confidence():
    chunks = [(box(0, 0), "안녕", 0.9), (box(50, 0), "xx", 0.2), (box(100, 0), "하세요", 0.4)]

    text, confidence = gate(chunks, 0.4)

    assert text == "안녕 하세요"
    assert confidence == pytest.approx(0.65)


def test_gate_returns_nothing_when_no_chunk_is_confident():
    assert gate([(box(0, 0), "xx", 0.1)], 0.4) == ("", 0.0)
    assert gate([], 0.4) == ("", 0.0)


def test_gate_drops_blank_chunks():
    assert gate([(box(0, 0), "  ", 0.99), (box(50, 0), "글자", 0.8)], 0.4) == ("글자", 0.8)


def test_reading_order_is_top_to_bottom_then_left_to_right():
    chunks = [
        (box(200, 100), "넷", 0.9),
        (box(10, 103), "셋", 0.9),   # same line as 넷, a little lower
        (box(150, 0), "둘", 0.9),
        (box(0, 4), "하나", 0.9),
    ]
    assert [text for _, text, _ in reading_order(chunks)] == ["하나", "둘", "셋", "넷"]
