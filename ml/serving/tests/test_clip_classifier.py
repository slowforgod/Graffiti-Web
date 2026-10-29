"""The real OpenCLIP code path with a randomly initialised ViT-B-32, so no download and no skip.

A random model gives meaningless scores: these tests check shape, range and determinism, never accuracy.
"""

import hashlib
import io

import pytest
import torch
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from app.clip_classifier import ClipClassifier
from app.image_input import read_png
from app.main import classifier_factory, create_app
from app.prompts import parse_prompts

from .conftest import PROMPTS, THRESHOLDS, write_lock

ARCH = "ViT-B-32"
LIMIT = 2 * 1024 * 1024


@pytest.fixture(scope="session")
def random_weights(tmp_path_factory):
    import open_clip
    from safetensors.torch import save_file

    torch.manual_seed(0)
    path = tmp_path_factory.mktemp("weights") / "random.safetensors"
    save_file(open_clip.create_model(ARCH).state_dict(), str(path))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return path, digest


@pytest.fixture(scope="session")
def classifier(random_weights):
    return ClipClassifier(random_weights[0], parse_prompts(PROMPTS, THRESHOLDS), ARCH, crop=True, threads=1)


def doodle(transparent: bool) -> bytes:
    mode, background = ("RGBA", (0, 0, 0, 0)) if transparent else ("RGB", "white")
    image = Image.new(mode, (256, 256), background)
    ImageDraw.Draw(image).line([(20, 30), (200, 180), (60, 220)], fill="black", width=6)
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


def picture(transparent: bool = False) -> Image.Image:
    return read_png(doodle(transparent), LIMIT, 1024)


def test_scores_every_thresholded_category_between_0_and_1(classifier):
    scores = classifier.scores(picture())

    assert set(scores) == set(THRESHOLDS)
    assert all(0.0 <= v <= 1.0 for v in scores.values())


def test_same_image_gets_the_same_scores(classifier):
    assert classifier.scores(picture()) == classifier.scores(picture())


def test_transparent_background_scores_like_white(classifier):
    assert classifier.scores(picture(transparent=True)) == classifier.scores(picture(transparent=False))


def test_inference_uses_the_configured_thread_count(random_weights):
    ClipClassifier(random_weights[0], parse_prompts(PROMPTS, THRESHOLDS), ARCH, crop=True, threads=1)
    assert torch.get_num_threads() == 1


@pytest.mark.parametrize("arch", ["no-such-arch", "hf-hub:laion/CLIP-ViT-B-32-laion2B-s34B-b79K"])
def test_unknown_or_remote_arch_is_refused(random_weights, arch):
    with pytest.raises(Exception):
        ClipClassifier(random_weights[0], parse_prompts(PROMPTS, THRESHOLDS), arch, crop=True, threads=1)


def test_missing_weights_file_is_refused_without_a_download(tmp_path):
    with pytest.raises(FileNotFoundError):
        ClipClassifier(tmp_path / "laion2b_s34b_b79k", parse_prompts(PROMPTS, THRESHOLDS), ARCH, crop=True, threads=1)


def test_sentence_over_77_tokens_is_refused(random_weights):
    # Few words but many tokens: the word-count check in parse_prompts lets it through.
    long_sentence = " ".join(["qzxvkjwpfmgh"] * 30)
    prompts = parse_prompts(PROMPTS.replace("a crude line drawing of genitals", long_sentence), THRESHOLDS)
    with pytest.raises(ValueError, match="77 tokens"):
        ClipClassifier(random_weights[0], prompts, ARCH, crop=True, threads=1)


def test_server_loads_the_model_and_answers(tmp_path, random_weights):
    path, digest = random_weights
    settings = write_lock(tmp_path, None, digest, weights_path=path)
    c = TestClient(create_app(settings, factory=classifier_factory(threads=1)))

    health = c.get("/health")
    res = c.post("/v1/moderate", files={"image": ("d.png", doodle(False), "image/png")}, data={"requestId": "r"})

    assert health.status_code == 200
    assert health.json()["modelVersion"] == "test-1"
    assert res.status_code == 200
    assert set(res.json()["image"]["scores"]) == set(THRESHOLDS)
    assert res.json()["image"]["label"] in ("SAFE", "FLAGGED")


def test_torch_is_the_cpu_build():
    # D-36 and SEC-16: requirements.txt pins the CPU wheel; a CUDA build means the index line was lost.
    assert torch.version.cuda is None
