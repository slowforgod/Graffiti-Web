import hashlib

import pytest

from app.lock import LockError, read_lock, verify_file

from .conftest import THRESHOLDS, write_lock


def test_reads_a_configured_lock(tmp_path):
    settings = write_lock(tmp_path, b"weights")
    lock = read_lock(settings.models_lock)

    assert lock.image.configured
    assert lock.image.version == "test-1"
    assert lock.image.file == "model.bin"
    assert lock.image.thresholds == THRESHOLDS
    assert lock.ocr_version == "none"


def test_none_version_means_no_model(tmp_path):
    lock = read_lock(write_lock(tmp_path, None).models_lock)
    assert not lock.image.configured


def test_repository_lock_parses():
    from pathlib import Path

    lock = read_lock(Path(__file__).resolve().parents[2] / "models.lock")
    assert lock.image.version


@pytest.mark.parametrize(
    "body",
    [
        "not toml [",
        '[ocr]\nversion = "none"\n',
        '[image]\nversion = "v1"\nfile = "m.bin"\nsha256 = "abc"\n[image.thresholds]\nsexual = 0.5\n[ocr]\nversion = "none"\n',
        '[image]\nversion = "v1"\nfile = "../m.bin"\nsha256 = "' + "a" * 64 + '"\n[image.thresholds]\nsexual = 0.5\n[ocr]\nversion = "none"\n',
        '[image]\nversion = "v1"\nfile = "m.bin"\nsha256 = "' + "a" * 64 + '"\n[ocr]\nversion = "none"\n',
        '[image]\nversion = "v1"\nfile = "m.bin"\nsha256 = "' + "a" * 64 + '"\n[image.thresholds]\nsexual = 1.5\n[ocr]\nversion = "none"\n',
        '[image]\nversion = "v1"\nfile = "m.bin"\nsha256 = "' + "a" * 64 + '"\n[image.thresholds]\nsafe = 0.5\n[ocr]\nversion = "none"\n',
    ],
    ids=["syntax", "no-image", "bad-sha", "path-escape", "no-thresholds", "threshold-range", "safe-threshold"],
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
