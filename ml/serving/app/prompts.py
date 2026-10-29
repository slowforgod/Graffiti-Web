"""Reads the category sentences of a zero-shot model version (ml/prompts/<version>.toml). Pure logic.

Each category has positive sentences (what to catch) and negative sentences (the harmless doodle closest to it).
The classifier scores how much closer a doodle is to the positive side (ml/DEVELOPMENT.md section 5).
"""

import tomllib
from collections.abc import Iterable
from dataclasses import dataclass

# CLIP's text encoder reads 77 tokens including the start and end tokens and silently cuts the rest.
MAX_TOKENS = 77
_SIDES = ("positive", "negative")


class PromptsError(ValueError):
    """The prompts file does not match models.lock. The model is not loaded."""


@dataclass(frozen=True)
class CategoryPrompts:
    positive: tuple[str, ...]
    negative: tuple[str, ...]


Prompts = dict[str, CategoryPrompts]


def parse_prompts(text: str, categories: Iterable[str]) -> Prompts:
    """The sentences per category; the categories must be exactly the thresholded ones in models.lock."""
    try:
        data = tomllib.loads(text)
    except tomllib.TOMLDecodeError as e:
        raise PromptsError(f"cannot parse prompts file: {e}") from e

    expected = set(categories)
    missing = sorted(expected - data.keys())
    extra = sorted(data.keys() - expected)
    if missing or extra:
        raise PromptsError(f"prompt categories differ from thresholds: missing {missing}, extra {extra}")

    prompts: Prompts = {}
    for category in sorted(expected):
        table = data[category]
        if not isinstance(table, dict):
            raise PromptsError(f"{category} must be a table with positive and negative lists")
        unknown = sorted(table.keys() - set(_SIDES))
        if unknown:
            raise PromptsError(f"{category} has unknown keys {unknown}")
        prompts[category] = CategoryPrompts(*(_sentences(table, category, side) for side in _SIDES))
    return prompts


def _sentences(table: dict, category: str, side: str) -> tuple[str, ...]:
    values = table.get(side)
    if not isinstance(values, list) or not values:
        raise PromptsError(f"{category}.{side} must list at least one sentence")
    for value in values:
        if not isinstance(value, str) or not value.strip():
            raise PromptsError(f"{category}.{side} must hold non-empty strings")
        # Every word is at least one token, so this catches most overlong sentences without the tokenizer.
        # The classifier checks the exact token count when it loads.
        if len(value.split()) > MAX_TOKENS - 2:
            raise PromptsError(f"{category}.{side} has a sentence longer than {MAX_TOKENS} tokens")
    return tuple(values)
