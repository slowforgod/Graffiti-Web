import csv
import io
import json

from PIL import Image

import build_set
from build_set import (
    STROKE_WIDTHS,
    Entry,
    hate_symbol_entries,
    quickdraw_entries,
    render,
    swastika,
    team_entries,
    write_set,
)
from calibration import Sample
from metrics import BAD, OK


def png(entry_bytes: bytes) -> Image.Image:
    return Image.open(io.BytesIO(entry_bytes))


def quickdraw_line(key, recognized=True):
    drawing = [[[0, 100, 255], [0, 200, 255]], [[30, 60], [10, 10]]]
    return json.dumps({"word": "cat", "key_id": key, "recognized": recognized, "drawing": drawing})


def test_render_draws_on_white_within_the_doodle_size_limit():
    image = png(render([[(0.0, 0.0), (1.0, 1.0)]], seed=1))

    assert image.mode == "RGB"
    assert max(image.size) <= build_set.MAX_SIDE
    colors = {c for _, c in image.getcolors(1 << 20)}
    assert (255, 255, 255) in colors
    assert len(colors) > 1


def test_render_is_repeatable_per_seed():
    strokes = [[(0.0, 0.0), (1.0, 0.5), (0.2, 1.0)]]
    assert render(strokes, seed=7) == render(strokes, seed=7)


def test_quickdraw_keeps_recognized_drawings_one_drawer_each(tmp_path):
    path = tmp_path / "cat.ndjson"
    path.write_text("\n".join([quickdraw_line("1"), quickdraw_line("2", False), quickdraw_line("3")]) + "\n")

    entries = list(quickdraw_entries([path], per_file=10))

    assert [(e.label, e.category, e.drawer) for e in entries] == [(OK, None, "qd-1"), (OK, None, "qd-3")]


def test_quickdraw_stops_at_the_per_file_limit(tmp_path):
    path = tmp_path / "cat.ndjson"
    path.write_text("\n".join(quickdraw_line(str(i)) for i in range(5)) + "\n")
    assert len(list(quickdraw_entries([path], per_file=2))) == 2


def test_swastika_arms_turn_clockwise_not_like_the_buddhist_manji():
    # Image coordinates, y down: the top arm (0,-1) hooks to the right. The mirrored manji hooks left.
    assert [(0.0, 0.0), (0.0, -1.0), (1.0, -1.0)] in swastika()
    assert [(0.0, 0.0), (0.0, -1.0), (-1.0, -1.0)] not in swastika()


def test_hate_variants_are_bad_repeatable_and_grouped_into_drawers():
    first = list(hate_symbol_entries(25, seed=3))
    again = list(hate_symbol_entries(25, seed=3))

    assert [e.png for e in first] == [e.png for e in again]
    assert {(e.label, e.category) for e in first} == {(BAD, "HATE")}
    # Variants from one generator look alike, so they share drawers and stay on one side of the split.
    assert len({e.drawer for e in first}) == 3


def test_team_folder_reads_category_and_drawer_from_the_path(tmp_path):
    folder = tmp_path / "team" / "SEXUAL" / "drawer-a"
    folder.mkdir(parents=True)
    buf = io.BytesIO()
    Image.new("RGB", (8, 8), "white").save(buf, format="PNG")
    (folder / "1.png").write_bytes(buf.getvalue())

    entries = list(team_entries(tmp_path / "team"))

    assert [(e.label, e.category, e.drawer) for e in entries] == [(BAD, "SEXUAL", "team-drawer-a")]


def test_write_set_saves_images_and_a_manifest_calibrate_can_read(tmp_path):
    from calibrate import read_samples

    entries = [Entry(b"png-1", OK, None, "qd-1"), Entry(b"png-2", BAD, "HATE", "syn-0")]
    write_set(entries, tmp_path / "set")

    samples = read_samples(tmp_path / "set" / "manifest.csv")
    assert samples == [Sample("images/0.png", OK, None, "qd-1"), Sample("images/1.png", BAD, "HATE", "syn-0")]
    assert (tmp_path / "set" / "images" / "1.png").read_bytes() == b"png-2"
    with (tmp_path / "set" / "manifest.csv").open() as f:
        assert next(csv.reader(f)) == ["file", "label", "category", "drawer"]


def test_stroke_widths_match_the_app():
    import re
    from pathlib import Path

    config = Path(__file__).resolve().parents[3] / "backend" / "src" / "main" / "resources" / "application.yml"
    widths = re.search(r"stroke-widths: \[([^\]]*)\]", config.read_text(encoding="utf-8")).group(1)
    assert STROKE_WIDTHS == tuple(int(w) for w in widths.split(","))
