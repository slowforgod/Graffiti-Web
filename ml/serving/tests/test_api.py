import logging

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

from .conftest import png_bytes, write_lock

OCR_TEXT = "비밀글자"


class FakeClassifier:
    def __init__(self, scores):
        self._scores = scores

    def scores(self, image):
        return self._scores


class FailingClassifier:
    def scores(self, image):
        raise RuntimeError("weights exploded: secret detail")


class FakeOcr:
    version = "fake-ocr-1"

    def read(self, image):
        return OCR_TEXT, 0.62


def client(tmp_path, classifier, weights=b"weights", sha256=None, ocr=None, **lock):
    settings = write_lock(tmp_path, weights, sha256, **lock)
    app = create_app(settings, factory=(lambda path, prompts, image: classifier) if classifier else None, ocr=ocr)
    return TestClient(app)


def moderate(c, data, request_id="req-1"):
    return c.post("/v1/moderate", files={"image": ("d.png", data, "image/png")}, data={"requestId": request_id})


def test_moderate_returns_api_shape_and_flags(tmp_path):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.81, "HATE": 0.04}))

    res = moderate(c, png_bytes())

    assert res.status_code == 200
    body = res.json()
    assert body["modelVersion"] == "test-1"
    assert body["image"] == {
        "label": "FLAGGED",
        "scores": {"SEXUAL": 0.81, "HATE": 0.04},
    }
    assert body["ocr"] == {"text": "", "confidence": 0.0}
    assert isinstance(body["latencyMs"], int)


def test_moderate_safe_below_thresholds(tmp_path):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.1, "HATE": 0.59}))
    assert moderate(c, png_bytes()).json()["image"]["label"] == "SAFE"


@pytest.mark.parametrize("data", [b"plain text", png_bytes(fmt="JPEG"), png_bytes(1025, 1025)], ids=["text", "jpeg", "big"])
def test_invalid_images_are_400(tmp_path, data):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.1}))
    res = moderate(c, data)
    assert res.status_code == 400
    assert res.json() == {"error": {"code": "INVALID_IMAGE"}}


def test_oversized_upload_is_400(tmp_path):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.1}))
    res = moderate(c, png_bytes() + b"\0" * (2 * 1024 * 1024))
    assert res.status_code == 400


def test_health_up_when_model_loaded(tmp_path):
    c = client(tmp_path, FakeClassifier({}))
    res = c.get("/health")
    assert res.status_code == 200
    assert res.json() == {"status": "UP", "modelVersion": "test-1", "ocrVersion": "none"}


def test_no_model_answers_503(tmp_path):
    c = client(tmp_path, None, weights=None)
    assert c.get("/health").status_code == 503
    assert c.get("/health").json()["status"] == "DOWN"
    res = moderate(c, png_bytes())
    assert res.status_code == 503
    assert res.json() == {"error": {"code": "MODEL_NOT_LOADED"}}


def test_checksum_mismatch_refuses_the_model(tmp_path):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.9}), sha256="0" * 64)
    assert c.get("/health").status_code == 503
    assert moderate(c, png_bytes()).status_code == 503


def test_factory_gets_the_verified_files_and_the_lock_entry(tmp_path):
    seen = {}

    def factory(path, prompts, image):
        seen.update(path=path, prompts=prompts, image=image)
        return FakeClassifier({})

    settings = write_lock(tmp_path, b"weights")
    TestClient(create_app(settings, factory=factory))

    assert seen["path"] == settings.model_dir / "model.safetensors"
    assert set(seen["prompts"]) == {"SEXUAL", "HATE"}
    assert seen["image"].arch == "ViT-B-32"


@pytest.mark.parametrize(
    "lock",
    [
        {"prompts_sha256": "0" * 64},
        {"prompts": None},
        {"prompts": '[SEXUAL]\npositive = ["a"]\nnegative = ["b"]\n'},
    ],
    ids=["prompts-checksum", "prompts-missing", "prompts-categories-differ-from-thresholds"],
)
def test_bad_prompts_refuse_the_model(tmp_path, lock):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.9}), **lock)
    assert c.get("/health").status_code == 503
    assert moderate(c, png_bytes()).status_code == 503


def test_factory_failure_refuses_the_model_instead_of_crashing(tmp_path, caplog):
    def factory(path, prompts, image):
        raise RuntimeError("unknown arch: secret detail")

    with caplog.at_level(logging.INFO):
        c = TestClient(create_app(write_lock(tmp_path, b"weights"), factory=factory))
    assert c.get("/health").status_code == 503
    assert "secret detail" not in caplog.text


def test_missing_factory_refuses_the_model(tmp_path):
    c = client(tmp_path, None)
    assert c.get("/health").status_code == 503


def test_model_failure_is_500_without_details(tmp_path, caplog):
    c = client(tmp_path, FailingClassifier())
    with caplog.at_level(logging.INFO):
        res = moderate(c, png_bytes())
    assert res.status_code == 500
    assert res.json() == {"error": {"code": "MODEL_ERROR"}}
    assert "secret detail" not in res.text
    assert "secret detail" not in caplog.text


def test_logs_carry_no_ocr_text_or_scores(tmp_path, caplog):
    c = client(tmp_path, FakeClassifier({"SEXUAL": 0.8123}), ocr=FakeOcr())
    with caplog.at_level(logging.DEBUG):
        res = moderate(c, png_bytes(), request_id="req-42")
    assert res.json()["ocr"] == {"text": OCR_TEXT, "confidence": 0.62}
    assert "req-42" in caplog.text
    assert OCR_TEXT not in caplog.text
    assert "0.8123" not in caplog.text


def test_api_docs_are_not_exposed(tmp_path):
    c = client(tmp_path, FakeClassifier({}))
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert c.get(path).status_code == 404
