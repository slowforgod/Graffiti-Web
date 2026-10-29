import hashlib

import pytest

from app.lock import LockError, read_lock, verify_file

from .conftest import THRESHOLDS, write_lock


def test_reads_a_configured_lock(tmp_path):
    settings = write_lock(tmp_path, b"weights")
    lock = read_lock(settings.models_lock)

    assert lock.image.configured
    assert lock.image.version == "test-1"
    assert lock.image.arch == "ViT-B-32"
    assert lock.image.file == "model.safetensors"
    assert lock.image.prompts == "test-1.toml"
    assert lock.image.crop_to_content is True
    assert lock.image.thresholds == THRESHOLDS
    assert lock.ocr_version == "none"


def test_none_version_means_no_model(tmp_path):
    lock = read_lock(write_lock(tmp_path, None).models_lock)
    assert not lock.image.configured


def test_repository_lock_parses():
    from pathlib import Path

    lock = read_lock(Path(__file__).resolve().parents[2] / "models.lock")
    assert lock.image.version


GOOD = {
    "version": '"v1"',
    "arch": '"ViT-B-32"',
    "file": '"m.safetensors"',
    "sha256": '"' + "a" * 64 + '"',
    "prompts": '"v1.toml"',
    "prompts_sha256": '"' + "b" * 64 + '"',
    "crop_to_content": "true",
}


def lock_body(thresholds: str = "SEXUAL = 0.5", **image) -> str:
    keys = {**GOOD, **image}
    lines = "".join(f"{k} = {v}\n" for k, v in keys.items() if v is not None)
    table = f"[image.thresholds]\n{thresholds}\n" if thresholds is not None else ""
    return f"[image]\n{lines}{table}[ocr]\nversion = \"none\"\n"


def test_lock_body_helper_builds_a_valid_lock(tmp_path):
    path = tmp_path / "models.lock"
    path.write_text(lock_body(), encoding="utf-8")
    assert read_lock(path).image.configured


@pytest.mark.parametrize(
    "body",
    [
        "not toml [",
        '[ocr]\nversion = "none"\n',
        lock_body(sha256='"abc"'),
        lock_body(file='"../m.safetensors"'),
        lock_body(thresholds=None),
        lock_body(thresholds="SEXUAL = 1.5"),
        lock_body(thresholds="safe = 0.5"),
        lock_body(arch=None),
        lock_body(prompts=None),
        lock_body(prompts='"../v1.toml"'),
        lock_body(prompts_sha256='"abc"'),
        lock_body(crop_to_content=None),
        lock_body(crop_to_content='"yes"'),
    ],
    ids=[
        "syntax", "no-image", "bad-sha", "path-escape", "no-thresholds", "threshold-range", "safe-threshold",
        "no-arch", "no-prompts", "prompts-path-escape", "bad-prompts-sha", "no-crop", "crop-not-bool",
    ],
)
def test_malformed_lock_is_rejected(tmp_path, body):
    path = tmp_path / "models.lock"
    path.write_text(body, encoding="utf-8")
    with pytest.raises(LockError):
        read_lock(path)


def test_verify_file_checks_sha256(tmp_path):
    path = tmp_path / "w.bin"
    path.write_bytes(b"weights")
    good = hashlib.sha256(b"weights").hexdigest()

    assert verify_file(path, good)
    assert not verify_file(path, "0" * 64)
    assert not verify_file(tmp_path / "missing.bin", good)
