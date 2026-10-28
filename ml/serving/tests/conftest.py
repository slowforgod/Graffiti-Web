import hashlib
import io
from pathlib import Path

import pytest
from PIL import Image

from app.settings import Settings

THRESHOLDS = {"SEXUAL": 0.5, "HATE": 0.6}

PROMPTS = """\
[SEXUAL]
positive = ["a crude line drawing of genitals"]
negative = ["a crude line drawing of a person"]

[HATE]
positive = ["a doodle of a hate symbol", "a doodle of a nazi flag"]
negative = ["a doodle of a simple shape", "a doodle of a flag"]
"""


def png_bytes(width: int = 64, height: int = 64, fmt: str = "PNG") -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (width, height), "white").save(buf, format=fmt)
    return buf.getvalue()


def write_lock(
    tmp_path: Path,
    weights: bytes | None,
    sha256: str | None = None,
    prompts: str | None = PROMPTS,
    prompts_sha256: str | None = None,
    arch: str = "ViT-B-32",
    crop_to_content: bool = True,
) -> Settings:
    """Writes models.lock, the weights file and the prompts file the way ml/ lays them out."""
    model_dir = tmp_path / "models"
    model_dir.mkdir()
    prompts_dir = tmp_path / "prompts"
    prompts_dir.mkdir()
    if weights is None:
        lock = '[image]\nversion = "none"\n\n[ocr]\nversion = "none"\n'
    else:
        (model_dir / "model.safetensors").write_bytes(weights)
        digest = sha256 or hashlib.sha256(weights).hexdigest()
        if prompts is not None:
            (prompts_dir / "test-1.toml").write_text(prompts, encoding="utf-8")
        prompts_digest = prompts_sha256 or hashlib.sha256((prompts or "").encode()).hexdigest()
        thresholds = "\n".join(f"{k} = {v}" for k, v in THRESHOLDS.items())
        lock = (
            f'[image]\nversion = "test-1"\narch = "{arch}"\nfile = "model.safetensors"\n'
            f'sha256 = "{digest}"\nuri = "s3://bucket/models/test-1/model.safetensors"\n'
            f'prompts = "test-1.toml"\nprompts_sha256 = "{prompts_digest}"\n'
            f"crop_to_content = {str(crop_to_content).lower()}\n\n"
            f"[image.thresholds]\n{thresholds}\n\n[ocr]\nversion = \"none\"\n"
        )
    lock_path = tmp_path / "models.lock"
    lock_path.write_text(lock, encoding="utf-8")
    return Settings(models_lock=lock_path, model_dir=model_dir, prompts_dir=prompts_dir)


@pytest.fixture
def png() -> bytes:
    return png_bytes()
