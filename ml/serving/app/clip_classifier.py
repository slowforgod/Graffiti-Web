"""OpenCLIP zero-shot classifier (D-37): one paired score per category, between 0 and 1.

For each category the score is sigmoid(logit_scale * (cos(image, positive) - cos(image, negative))), where
positive and negative are the averaged sentence embeddings from the prompts file. Categories are scored
independently, so adding one never changes another's score (ml/DEVELOPMENT.md section 5).
"""

from collections.abc import Sequence
from pathlib import Path

import open_clip
import torch
from PIL import Image

from .preprocess import crop_to_content
from .prompts import Prompts


class ClipClassifier:
    def __init__(self, weights: Path, prompts: Prompts, arch: str, crop: bool, threads: int):
        # A schema such as "hf-hub:" would make open_clip fetch from the network (SEC-17).
        if ":" in arch:
            raise ValueError("arch must be a built-in open_clip model name")
        if not weights.is_file():
            # open_clip would treat a missing path as a pretrained tag name and try to download it.
            raise FileNotFoundError("weights file not found")
        torch.set_num_threads(threads)
        self._model, self._preprocess = open_clip.create_model_from_pretrained(
            arch, pretrained=str(weights), device="cpu"
        )
        self._model.eval()
        self._tokenizer = open_clip.get_tokenizer(arch)
        self._crop = crop
        self._categories = sorted(prompts)
        with torch.inference_mode():
            self._positive = torch.stack([self._embed(prompts[c].positive) for c in self._categories])
            self._negative = torch.stack([self._embed(prompts[c].negative) for c in self._categories])
            self._logit_scale = self._model.logit_scale.exp().item()

    def scores(self, image: Image.Image) -> dict[str, float]:
        if self._crop:
            image = crop_to_content(image)
        with torch.inference_mode():
            pixels = self._preprocess(image).unsqueeze(0)
            embedding = _normalize(self._model.encode_image(pixels))[0]
            margin = self._positive @ embedding - self._negative @ embedding
            values = torch.sigmoid(self._logit_scale * margin)
        return {c: float(v) for c, v in zip(self._categories, values)}

    def _embed(self, sentences: Sequence[str]) -> torch.Tensor:
        """The normalized mean of the normalized sentence embeddings."""
        limit = self._tokenizer.context_length
        for sentence in sentences:
            # +2 for the start and end tokens. The tokenizer would silently cut a longer sentence.
            if len(self._tokenizer.encode(sentence)) + 2 > limit:
                raise ValueError(f"a prompt sentence is longer than {limit} tokens")
        embeddings = _normalize(self._model.encode_text(self._tokenizer(list(sentences))))
        return _normalize(embeddings.mean(dim=0, keepdim=True))[0]


def _normalize(x: torch.Tensor) -> torch.Tensor:
    return x / x.norm(dim=-1, keepdim=True)
