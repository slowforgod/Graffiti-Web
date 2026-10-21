"""Validates the uploaded doodle image before it reaches a model."""

import io

from PIL import Image, UnidentifiedImageError

PNG = "PNG"


class InvalidImage(ValueError):
    pass


def read_png(data: bytes, max_bytes: int, max_side: int) -> Image.Image:
    if not data:
        raise InvalidImage("empty upload")
    if len(data) > max_bytes:
        raise InvalidImage("image too large")
    try:
        with Image.open(io.BytesIO(data)) as probe:
            if probe.format != PNG:
                raise InvalidImage("not a PNG")
            # Checked before decoding pixels so a huge image cannot exhaust memory.
            if probe.width > max_side or probe.height > max_side:
                raise InvalidImage("image dimensions too large")
        image = Image.open(io.BytesIO(data))
        image.load()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as e:
        raise InvalidImage("unreadable image") from e
    return image.convert("RGB")
