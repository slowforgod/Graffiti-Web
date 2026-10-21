import pytest

from app.image_input import InvalidImage, read_png

from .conftest import png_bytes


def test_accepts_png_within_limits():
    image = read_png(png_bytes(1024, 1024), max_bytes=2_000_000, max_side=1024)
    assert image.size == (1024, 1024)
    assert image.mode == "RGB"


@pytest.mark.parametrize(
    "data",
    [b"", b"not an image", png_bytes(fmt="JPEG"), png_bytes(1025, 10), png_bytes(10, 1025), png_bytes()[:40]],
    ids=["empty", "garbage", "jpeg", "too-wide", "too-tall", "truncated"],
)
def test_rejects_invalid_images(data):
    with pytest.raises(InvalidImage):
        read_png(data, max_bytes=2_000_000, max_side=1024)


def test_rejects_oversized_bytes():
    data = png_bytes()
    with pytest.raises(InvalidImage):
        read_png(data, max_bytes=len(data) - 1, max_side=1024)
