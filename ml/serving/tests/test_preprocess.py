from PIL import Image, ImageDraw

from app.preprocess import crop_to_content

WHITE = (255, 255, 255)


def blank(width=1024, height=1024):
    return Image.new("RGB", (width, height), WHITE)


def ink_box(image):
    """Bounding box of the non-white pixels."""
    return Image.eval(image.convert("L"), lambda v: 255 - v).getbbox()


def test_small_corner_drawing_fills_the_crop():
    image = blank()
    ImageDraw.Draw(image).rectangle([900, 900, 960, 940], outline=(0, 0, 0), width=3)

    cropped = crop_to_content(image)

    assert cropped.width == cropped.height
    left, top, right, bottom = ink_box(cropped)
    # The drawing now spans most of the crop instead of a 60 px corner of a 1024 px canvas.
    assert right - left >= 0.7 * cropped.width


def test_wide_drawing_is_padded_to_a_centred_square():
    image = blank()
    ImageDraw.Draw(image).line([(100, 500), (900, 500)], fill=(0, 0, 0), width=4)

    cropped = crop_to_content(image)

    assert cropped.width == cropped.height
    left, top, right, bottom = ink_box(cropped)
    centre = (top + bottom) / 2
    assert abs(centre - cropped.height / 2) <= 2


def test_blank_canvas_is_returned_whole():
    image = blank(300, 200)
    assert crop_to_content(image).size == (300, 200)


def test_faint_antialiasing_alone_counts_as_blank():
    image = blank(300, 200)
    image.putpixel((10, 10), (252, 252, 252))
    assert crop_to_content(image).size == (300, 200)
