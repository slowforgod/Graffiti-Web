"""Response bodies of docs/API.md section 7. Field names are the API's camelCase."""

from pydantic import BaseModel


class ImageResult(BaseModel):
    label: str
    scores: dict[str, float]


class OcrResult(BaseModel):
    text: str
    confidence: float


class ModerateResponse(BaseModel):
    modelVersion: str
    image: ImageResult
    ocr: OcrResult
    latencyMs: int


class HealthResponse(BaseModel):
    status: str
    modelVersion: str
    ocrVersion: str
