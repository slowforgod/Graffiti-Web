"""The real EasyOCR code path with randomly initialised weights, so no download and no skip.

EasyOCR checks its files against md5 sums of the official releases. The random files cannot match, so these
tests replace that one check; the server's own sha256 check (models.lock) still runs. A random model reads
nonsense: the tests check shape, range, determinism and that nothing is downloaded, never accuracy.
"""

import hashlib

import easyocr.easyocr
import pytest
import torch
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from app.lock import ModelFile, OcrModelLock
from app.main import create_app, easyocr_factory
from app.ocr import EasyOcrEngine

from .conftest import png_bytes, write_lock
from .test_api import FakeClassifier

DETECTOR = easyocr.easyocr.detection_models["craft"]
RECOGNIZER = easyocr.easyocr.recognition_models["gen2"]["korean_g2"]


@pytest.fixture(scope="session")
def random_ocr_files(tmp_path_factory):
    from easyocr.craft import CRAFT
    from easyocr.model.vgg_model import Model
    from easyocr.utils import CTCLabelConverter

    torch.manual_seed(0)
    folder = tmp_path_factory.mktemp("ocr")
    torch.save(CRAFT().state_dict(), folder / DETECTOR["filename"])
    classes = len(CTCLabelConverter(RECOGNIZER["characters"], {}, {}).character)
    recognizer = Model(num_class=classes, input_channel=1, output_channel=256, hidden_size=256)
    # EasyOCR strips a "module." prefix from every recognizer key, as saved from DataParallel.
    torch.save({f"module.{k}": v for k, v in recognizer.state_dict().items()}, folder / RECOGNIZER["filename"])
    return {p.name: p.read_bytes() for p in folder.iterdir()}


@pytest.fixture
def official_md5(monkeypatch):
    """Make EasyOCR's md5 check accept the random files as the official ones."""
    expected = {DETECTOR["filename"]: DETECTOR["md5sum"], RECOGNIZER["filename"]: RECOGNIZER["md5sum"]}
    monkeypatch.setattr(easyocr.easyocr, "calculate_md5", lambda path: expected[path.rsplit("/", 1)[-1]])


@pytest.fixture
def no_download(monkeypatch):
    def refuse(*args, **kwargs):
        raise AssertionError("EasyOCR tried to download a model")

    monkeypatch.setattr(easyocr.easyocr, "download_and_unzip", refuse)


def ocr_lock(files) -> OcrModelLock:
    detector, recognizer = (ModelFile(name, hashlib.sha256(data).hexdigest()) for name, data in files.items())
    return OcrModelLock("ocr-test-1", "easyocr", ("ko", "en"), 0.0, detector, recognizer)


def handwriting() -> Image.Image:
    image = Image.new("RGB", (320, 120), "white")
    draw = ImageDraw.Draw(image)
    draw.line([(20, 30), (60, 90), (100, 30)], fill="black", width=6)
    draw.line([(140, 30), (140, 90), (200, 90)], fill="black", width=6)
    return image


def test_reads_text_and_confidence_from_local_files(tmp_path, random_ocr_files, official_md5, no_download):
    for name, data in random_ocr_files.items():
        (tmp_path / name).write_bytes(data)
    engine = EasyOcrEngine(tmp_path, ocr_lock(random_ocr_files), threads=1)

    text, confidence = engine.read(handwriting())

    assert isinstance(text, str)
    assert 0.0 <= confidence <= 1.0
    assert engine.read(handwriting()) == (text, confidence)
    assert engine.version == "ocr-test-1"


def test_missing_file_fails_without_downloading(tmp_path, random_ocr_files, official_md5, no_download):
    (tmp_path / DETECTOR["filename"]).write_bytes(random_ocr_files[DETECTOR["filename"]])

    with pytest.raises(FileNotFoundError):
        EasyOcrEngine(tmp_path, ocr_lock(random_ocr_files), threads=1)


def test_server_loads_ocr_and_answers(tmp_path, random_ocr_files, official_md5, no_download):
    settings = write_lock(tmp_path, b"weights", ocr=random_ocr_files)
    c = TestClient(
        create_app(settings, factory=lambda *a: FakeClassifier({"SEXUAL": 0.1}), ocr_factory=easyocr_factory(1))
    )

    health = c.get("/health")
    res = c.post("/v1/moderate", files={"image": ("d.png", png_bytes(), "image/png")}, data={"requestId": "r"})

    assert health.status_code == 200
    assert health.json()["ocrVersion"] == "ocr-test-1"
    assert res.status_code == 200
    assert set(res.json()["ocr"]) == {"text", "confidence"}
