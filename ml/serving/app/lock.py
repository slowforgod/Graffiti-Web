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
class ModelFile:
    """One weights file in MODEL_DIR, verified by sha256 before it is read."""

    file: str
    sha256: str
    uri: str | None = None


@dataclass(frozen=True)
class OcrModelLock:
    version: str
    engine: str | None = None
    langs: tuple[str, ...] = ()
    # Text chunks below this confidence are dropped (docs/specs/07b-ml-moderation.md).
    min_confidence: float = 0.0
    detector: ModelFile | None = None
    recognizer: ModelFile | None = None

    @property
    def configured(self) -> bool:
        return self.version != NONE

    @property
    def files(self) -> tuple[ModelFile, ...]:
        return tuple(f for f in (self.detector, self.recognizer) if f is not None)


@dataclass(frozen=True)
class ModelsLock:
    image: ImageModelLock
    ocr: OcrModelLock


OCR_ENGINES = ("easyocr",)


def read_lock(path: Path) -> ModelsLock:
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as e:
        raise LockError(f"cannot read {path}: {e}") from e

    image = data.get("image")
    ocr = data.get("ocr")
    if not isinstance(image, dict) or not isinstance(ocr, dict):
        raise LockError("models.lock needs [image] and [ocr] tables")
    return ModelsLock(_image(image), _ocr(ocr))


def _image(image: dict) -> ImageModelLock:
    version = _required_str(image, "version", "image")
    if version == NONE:
        return ImageModelLock(NONE)

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
    return ImageModelLock(
        version, file, sha256, image.get("uri"), parsed, arch, prompts, prompts_sha256, crop_to_content
    )


def _ocr(ocr: dict) -> OcrModelLock:
    version = _required_str(ocr, "version", "ocr")
    if version == NONE:
        return OcrModelLock(NONE)
    engine = _required_str(ocr, "engine", "ocr")
    if engine not in OCR_ENGINES:
        raise LockError(f"ocr.engine must be one of {OCR_ENGINES}")
    langs = ocr.get("langs")
    if not isinstance(langs, list) or not langs or not all(isinstance(x, str) and x for x in langs):
        raise LockError("ocr.langs must list at least one language code")
    min_confidence = ocr.get("min_confidence")
    number = isinstance(min_confidence, (int, float)) and not isinstance(min_confidence, bool)
    if not number or not 0 <= min_confidence <= 1:
        raise LockError("ocr.min_confidence must be between 0 and 1")
    detector, recognizer = _model_file(ocr, "detector"), _model_file(ocr, "recognizer")
    return OcrModelLock(version, engine, tuple(langs), float(min_confidence), detector, recognizer)


def _model_file(ocr: dict, key: str) -> ModelFile:
    table = ocr.get(key)
    if not isinstance(table, dict):
        raise LockError(f"models.lock needs an [ocr.{key}] table")
    file = _required_str(table, "file", f"ocr.{key}")
    if Path(file).name != file:
        raise LockError(f"ocr.{key}.file must be a bare file name inside MODEL_DIR")
    sha256 = _required_str(table, "sha256", f"ocr.{key}")
    if not _SHA256.fullmatch(sha256):
        raise LockError(f"ocr.{key}.sha256 must be 64 lowercase hex characters")
    return ModelFile(file, sha256, table.get("uri"))


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
