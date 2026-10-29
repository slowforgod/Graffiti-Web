"""Image preparation before the classifier sees a doodle. Pure PIL, no model code.

The numbers here change what the model sees, so they belong to the model version: changing one needs a new
version in models.lock and a new evaluation (ml/DEVELOPMENT.md section 4), not an environment variable.
"""

from PIL import Image

WHITE = (255, 255, 255)
# Grey levels at or above this count as background, so faint anti-aliasing alone does not make a crop.
BACKGROUND_LEVEL = 250
# Space kept around the drawing, as a share of its longer side.
MARGIN = 0.1


def crop_to_content(image: Image.Image) -> Image.Image:
    """The drawn part of an RGB doodle on white, padded to a centred square.

    The model looks at a 224 px square, so a small drawing in the corner of a 1024 px canvas would shrink to a
    few pixels. A blank canvas is returned unchanged.
    """
    ink = image.convert("L").point(lambda v: 255 if v < BACKGROUND_LEVEL else 0)
    box = ink.getbbox()
    if box is None:
        return image
    left, top, right, bottom = box
    side = max(right - left, bottom - top)
    side += 2 * max(1, round(side * MARGIN))
    square = Image.new("RGB", (side, side), WHITE)
    square.paste(image.crop(box), ((side - (right - left)) // 2, (side - (bottom - top)) // 2))
    return square
