"""ML moderation server (docs/API.md section 7). Internal network only (SEC-17)."""

import logging
import time

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse

from .decision import decide
from .image_input import InvalidImage, read_png
from .lock import read_lock
from .models import ClassifierFactory, OcrEngine, Registry
from .schemas import HealthResponse, ImageResult, ModerateResponse, OcrResult
from .settings import Settings

log = logging.getLogger("ml.moderate")


def classifier_factory() -> ClassifierFactory | None:
    """The image classifier to load. None until the ML part adds the zero-shot model (D-37)."""
    return None


def error(status: int, code: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code}})


def create_app(
    settings: Settings | None = None, factory: ClassifierFactory | None = None, ocr: OcrEngine | None = None
) -> FastAPI:
    settings = settings or Settings.from_env()
    registry = Registry.load(read_lock(settings.models_lock), settings.model_dir, factory, ocr)
    app = FastAPI(title="graffiti-ml", docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health", response_model=HealthResponse)
    def health():
        body = HealthResponse(
            status="UP" if registry.ready else "DOWN",
            modelVersion=registry.lock.image.version,
            ocrVersion=registry.ocr.version,
        )
        if not registry.ready:
            return JSONResponse(status_code=503, content=body.model_dump())
        return body

    @app.post("/v1/moderate", response_model=ModerateResponse)
    async def moderate(image: UploadFile = File(...), requestId: str = Form(..., max_length=64)):
        started = time.perf_counter()
        # Read one byte past the limit so an oversized upload is rejected without buffering it all.
        data = await image.read(settings.max_image_bytes + 1)
        try:
            picture = read_png(data, settings.max_image_bytes, settings.max_image_side)
        except InvalidImage:
            log.info("request=%s rejected: invalid image", requestId)
            return error(400, "INVALID_IMAGE")
        if not registry.ready:
            return error(503, "MODEL_NOT_LOADED")

        try:
            scores = {k: float(v) for k, v in registry.classifier.scores(picture).items()}
            text, confidence = registry.ocr.read(picture)
        except Exception as e:
            # Any model failure becomes a 500 without details; Spring treats it as no verdict (INV-13).
            log.error("request=%s model failure: %s", requestId, type(e).__name__)
            return error(500, "MODEL_ERROR")

        label = decide(scores, registry.lock.image.thresholds)
        latency_ms = round((time.perf_counter() - started) * 1000)
        # SEC-17: no OCR text, scores or image in logs.
        log.info("request=%s label=%s latencyMs=%d", requestId, label, latency_ms)
        return ModerateResponse(
            modelVersion=registry.lock.image.version,
            image=ImageResult(label=label, scores=scores),
            ocr=OcrResult(text=text, confidence=confidence),
            latencyMs=latency_ms,
        )

    return app


def build() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app(factory=classifier_factory())
