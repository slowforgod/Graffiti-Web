"""Reads ml/models.lock and verifies model files against it (SEC-17)."""

import hashlib
import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

NONE = "none"
_SHA256 = re.compile(r"[0-9a-f]{64}")


class LockError(ValueError):
    """models.lock is malformed. The server refuses to start so a bad deploy shows at once."""


@dataclass(frozen=True)
class ImageModelLock:
    version: str
    file: str | None = None
    sha256: str | None = None
    uri: str | None = None
    thresholds: dict[str, float] = field(default_factory=dict)
    # open_clip architecture name, e.g. "ViT-B-32".
    arch: str | None = None
    # Category sentences in ml/prompts/, locked like the weights because they change every score.
    prompts: str | None = None
    prompts_sha256: str | None = None
    crop_to_content: bool = False

    @property
    def configured(self) -> bool:
        return self.version != NONE


@dataclass(frozen=True)
class ModelsLock:
    image: ImageModelLock
    ocr_version: str


def read_lock(path: Path) -> ModelsLock:
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as e:
        raise LockError(f"cannot read {path}: {e}") from e

    image = data.get("image")
    ocr = data.get("ocr")
    if not isinstance(image, dict) or not isinstance(ocr, dict):
        raise LockError("models.lock needs [image] and [ocr] tables")
    version = _required_str(image, "version", "image")
    ocr_version = _required_str(ocr, "version", "ocr")

    if version == NONE:
        return ModelsLock(ImageModelLock(NONE), ocr_version)

    arch = _required_str(image, "arch", "image")
    file = _bare_name(image, "file", "MODEL_DIR")
    sha256 = _sha256(image, "sha256")
    prompts = _bare_name(image, "prompts", "PROMPTS_DIR")
    prompts_sha256 = _sha256(image, "prompts_sha256")
    crop_to_content = image.get("crop_to_content")
    if not isinstance(crop_to_content, bool):
        raise LockError("image.crop_to_content must be true or false")
    thresholds = image.get("thresholds")
    if not isinstance(thresholds, dict) or not thresholds:
        raise LockError("image.thresholds must list at least one category")
    parsed: dict[str, float] = {}
    for category, value in thresholds.items():
        if category == "safe":
            raise LockError("'safe' cannot have a threshold")
        if not isinstance(value, (int, float)) or not 0.0 <= value <= 1.0:
            raise LockError(f"image.thresholds.{category} must be between 0 and 1")
        parsed[category] = float(value)
    return ModelsLock(
        ImageModelLock(
            version, file, sha256, image.get("uri"), parsed, arch, prompts, prompts_sha256, crop_to_content
        ),
        ocr_version,
    )


def verify_file(path: Path, expected_sha256: str) -> bool:
    """True when the file exists and its sha256 matches the lock."""
    if not path.is_file():
        return False
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest() == expected_sha256


def _bare_name(table: dict, key: str, directory: str) -> str:
    value = _required_str(table, key, "image")
    if Path(value).name != value:
        raise LockError(f"image.{key} must be a bare file name inside {directory}")
    return value


def _sha256(table: dict, key: str) -> str:
    value = _required_str(table, key, "image")
    if not _SHA256.fullmatch(value):
        raise LockError(f"image.{key} must be 64 lowercase hex characters")
    return value


def _required_str(table: dict, key: str, name: str) -> str:
    value = table.get(key)
    if not isinstance(value, str) or not value:
        raise LockError(f"{name}.{key} must be a non-empty string")
    return value
