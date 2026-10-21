"""Server settings from the environment. Every tunable number of the ML server lives here."""

import os
from dataclasses import dataclass
from pathlib import Path

# API.md section 7: the rendered doodle is at most 1024x1024.
DEFAULT_MAX_IMAGE_SIDE = 1024
# Same as the doodle payload limit on the Spring side (D-30).
DEFAULT_MAX_IMAGE_BYTES = 2 * 1024 * 1024


@dataclass(frozen=True)
class Settings:
    models_lock: Path
    model_dir: Path
    max_image_side: int = DEFAULT_MAX_IMAGE_SIDE
    max_image_bytes: int = DEFAULT_MAX_IMAGE_BYTES

    @staticmethod
    def from_env() -> "Settings":
        root = Path(__file__).resolve().parents[2]
        return Settings(
            models_lock=Path(os.environ.get("MODELS_LOCK", root / "models.lock")),
            model_dir=Path(os.environ.get("MODEL_DIR", root / "models")),
            max_image_side=int(os.environ.get("MAX_IMAGE_SIDE", DEFAULT_MAX_IMAGE_SIDE)),
            max_image_bytes=int(os.environ.get("MAX_IMAGE_BYTES", DEFAULT_MAX_IMAGE_BYTES)),
        )
