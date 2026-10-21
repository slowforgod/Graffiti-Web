import hashlib
import io
from pathlib import Path

import pytest
from PIL import Image

from app.settings import Settings

THRESHOLDS = {"sexual": 0.5, "violence": 0.6, "hate_symbol": 0.5}


def png_bytes(width: int = 64, height: int = 64, fmt: str = "PNG") -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (width, height), "white").save(buf, format=fmt)
    return buf.getvalue()


def write_lock(tmp_path: Path, weights: bytes | None, sha256: str | None = None) -> Settings:
    model_dir = tmp_path / "models"
    model_dir.mkdir()
    if weights is None:
        lock = '[image]\nversion = "none"\n\n[ocr]\nversion = "none"\n'
    else:
        (model_dir / "model.bin").write_bytes(weights)
        digest = sha256 or hashlib.sha256(weights).hexdigest()
        thresholds = "\n".join(f"{k} = {v}" for k, v in THRESHOLDS.items())
        lock = (
            '[image]\nversion = "test-1"\nfile = "model.bin"\n'
            f'sha256 = "{digest}"\nuri = "s3://bucket/models/test-1/model.bin"\n\n'
            f"[image.thresholds]\n{thresholds}\n\n[ocr]\nversion = \"none\"\n"
        )
    lock_path = tmp_path / "models.lock"
    lock_path.write_text(lock, encoding="utf-8")
    return Settings(models_lock=lock_path, model_dir=model_dir)


@pytest.fixture
def png() -> bytes:
    return png_bytes()
