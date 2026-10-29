import io

import pytest
from PIL import Image, ImageDraw

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


def test_transparent_background_becomes_white():
    # A doodle rendered on a transparent canvas: black stroke, fully transparent (0, 0, 0, 0) background.
    canvas = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    ImageDraw.Draw(canvas).line([(8, 32), (56, 32)], fill=(0, 0, 0, 255), width=4)
    buf = io.BytesIO()
    canvas.save(buf, format="PNG")

    image = read_png(buf.getvalue(), max_bytes=2_000_000, max_side=1024)

    assert image.mode == "RGB"
    assert image.getpixel((2, 2)) == (255, 255, 255)
    assert image.getpixel((32, 32)) == (0, 0, 0)
