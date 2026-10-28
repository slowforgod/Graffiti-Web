import json

from PIL import Image

from ocr_eval import prepare, read_aihub, read_manifest


def test_manifest_rows_keep_text_and_optional_category(tmp_path):
    manifest = tmp_path / "manifest.csv"
    manifest.write_text("file,text,category\na.png,안녕,\nb.png,욕,PROFANITY\n", encoding="utf-8")

    assert read_manifest(manifest) == [(tmp_path / "a.png", "안녕", None), (tmp_path / "b.png", "욕", "PROFANITY")]


def test_aihub_labels_give_text_only_for_images_present(tmp_path):
    images = tmp_path / "images" / "1_sentence"
    images.mkdir(parents=True)
    Image.new("RGB", (8, 8), "white").save(images / "00000002.png")
    labels = tmp_path / "labels.json"
    labels.write_text(
        json.dumps(
            {
                "images": [],
                "annotations": [
                    {"image_id": "00000002", "text": "문장", "attributes": {"gender": "여", "age": "28"}},
                    {"image_id": "00000003", "text": "없는 그림", "attributes": {}},
                ],
            }
        ),
        encoding="utf-8",
    )

    assert read_aihub(labels, tmp_path / "images") == [(images / "00000002.png", "문장", None)]


def test_prepare_shrinks_to_the_server_limit_on_white(tmp_path):
    path = tmp_path / "wide.png"
    Image.new("RGBA", (3700, 180), (0, 0, 0, 0)).save(path)

    image = prepare(path)

    assert image.mode == "RGB"
    assert max(image.size) == 1024
    assert image.getpixel((0, 0)) == (255, 255, 255)
