"""Validates the uploaded doodle image before it reaches a model."""

import io

from PIL import Image, UnidentifiedImageError

PNG = "PNG"
WHITE = (255, 255, 255)


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
    return on_white(image)


def on_white(image: Image.Image) -> Image.Image:
    """RGB with any transparency composited onto white.

    A plain convert("RGB") keeps the colour of transparent pixels, which is usually black, so a doodle drawn
    on a transparent canvas would become black strokes on black.
    """
    if image.mode == "P" and "transparency" in image.info:
        image = image.convert("RGBA")
    if image.mode in ("RGBA", "LA"):
        rgba = image.convert("RGBA")
        background = Image.new("RGBA", rgba.size, WHITE + (255,))
        return Image.alpha_composite(background, rgba).convert("RGB")
    return image.convert("RGB")
