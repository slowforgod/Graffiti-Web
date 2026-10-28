"""Model interfaces and the registry that decides whether the server can answer."""

import logging
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from PIL import Image

from .lock import ImageModelLock, ModelsLock, OcrModelLock, verify_file
from .prompts import Prompts, PromptsError, parse_prompts

log = logging.getLogger(__name__)


class ImageClassifier(Protocol):
    """Scores a doodle per category, e.g. a CLIP zero-shot classifier (D-37)."""

    def scores(self, image: Image.Image) -> Mapping[str, float]: ...


class OcrEngine(Protocol):
    """Reads handwriting from a doodle (D-35)."""

    version: str

    def read(self, image: Image.Image) -> tuple[str, float]: ...


class NoOcr:
    """Used when models.lock names no OCR engine: reads nothing."""

    version = "none"

    def read(self, image: Image.Image) -> tuple[str, float]:
        return "", 0.0


# Builds a classifier from the verified weights file, the parsed category sentences and the lock entry.
ClassifierFactory = Callable[[Path, Prompts, ImageModelLock], ImageClassifier]
# Builds the OCR engine from MODEL_DIR once the lock's files are verified.
OcrFactory = Callable[[Path, OcrModelLock], OcrEngine]


@dataclass(frozen=True)
class Registry:
    lock: ModelsLock
    classifier: ImageClassifier | None
    # None when models.lock names an OCR engine that could not be loaded.
    ocr: OcrEngine | None

    @property
    def ready(self) -> bool:
        return self.classifier is not None and self.ocr is not None

    @staticmethod
    def load(
        lock: ModelsLock,
        model_dir: Path,
        prompts_dir: Path,
        factory: ClassifierFactory | None,
        ocr_factory: OcrFactory | None = None,
    ) -> "Registry":
        image = lock.image
        ocr = _load_ocr(lock.ocr, model_dir, ocr_factory)
        if not image.configured:
            log.warning("No image model configured in models.lock; answering 503")
            return Registry(lock, None, ocr)
        if factory is None:
            log.error("models.lock names %s but no classifier factory is installed", image.version)
            return Registry(lock, None, ocr)
        path = model_dir / image.file
        if not verify_file(path, image.sha256):
            log.error("Model file for %s is missing or fails its sha256 check; not loading", image.version)
            return Registry(lock, None, ocr)
        prompts_path = prompts_dir / image.prompts
        if not verify_file(prompts_path, image.prompts_sha256):
            log.error("Prompts file for %s is missing or fails its sha256 check; not loading", image.version)
            return Registry(lock, None, ocr)
        try:
            prompts = parse_prompts(prompts_path.read_text(encoding="utf-8"), image.thresholds)
        except PromptsError as e:
            # The message names categories and keys only, never sentences.
            log.error("Prompts file for %s is invalid (%s); not loading", image.version, e)
            return Registry(lock, None, ocr)
        try:
            classifier = factory(path, prompts, image)
        except Exception as e:
            # A broken weights file or unknown arch leaves the server up and answering 503, like a missing model.
            log.error("Image model %s failed to load: %s", image.version, type(e).__name__)
            return Registry(lock, None, ocr)
        log.info("Loaded image model %s", image.version)
        return Registry(lock, classifier, ocr)


def _load_ocr(lock: OcrModelLock, model_dir: Path, factory: OcrFactory | None) -> OcrEngine | None:
    if not lock.configured:
        return NoOcr()
    if factory is None:
        log.error("models.lock names OCR %s but no OCR factory is installed", lock.version)
        return None
    for model_file in lock.files:
        if not verify_file(model_dir / model_file.file, model_file.sha256):
            log.error("OCR file for %s is missing or fails its sha256 check; not loading", lock.version)
            return None
    try:
        engine = factory(model_dir, lock)
    except Exception as e:
        log.error("OCR %s failed to load: %s", lock.version, type(e).__name__)
        return None
    log.info("Loaded OCR %s", lock.version)
    return engine
