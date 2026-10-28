import tomllib
from pathlib import Path

import pytest

from app.prompts import MAX_TOKENS, CategoryPrompts, PromptsError, parse_prompts

from .conftest import PROMPTS, THRESHOLDS


def test_parses_positive_and_negative_sentences_per_category():
    prompts = parse_prompts(PROMPTS, THRESHOLDS.keys())

    assert set(prompts) == set(THRESHOLDS)
    assert prompts["HATE"] == CategoryPrompts(
        positive=("a doodle of a hate symbol", "a doodle of a nazi flag"),
        negative=("a doodle of a simple shape", "a doodle of a flag"),
    )


@pytest.mark.parametrize(
    "body, message",
    [
        ("not toml [", "cannot parse"),
        ('[SEXUAL]\npositive = ["a"]\nnegative = ["b"]\n', "HATE"),
        (PROMPTS + '[PROFANITY]\npositive = ["a"]\nnegative = ["b"]\n', "PROFANITY"),
        (PROMPTS.replace('positive = ["a crude line drawing of genitals"]', "positive = []"), "SEXUAL"),
        (PROMPTS.replace('negative = ["a crude line drawing of a person"]\n', ""), "SEXUAL"),
        (PROMPTS.replace('"a crude line drawing of genitals"', '""'), "SEXUAL"),
        (PROMPTS.replace('"a crude line drawing of genitals"', "3"), "SEXUAL"),
        ('SEXUAL = "x"\n' + PROMPTS.split("\n\n")[1], "SEXUAL"),
        (PROMPTS.replace("[SEXUAL]\n", "[SEXUAL]\nextra = 1\n"), "extra"),
    ],
    ids=[
        "syntax", "missing-category", "extra-category", "empty-positive", "no-negative", "empty-sentence",
        "not-a-string", "not-a-table", "unknown-key",
    ],
)
def test_malformed_prompts_are_rejected_naming_the_problem(body, message):
    with pytest.raises(PromptsError, match=message):
        parse_prompts(body, THRESHOLDS.keys())


def test_long_sentences_are_rejected_before_the_tokenizer_cuts_them():
    # CLIP's tokenizer silently truncates at 77 tokens; a word is at least one token.
    long_sentence = " ".join(["word"] * MAX_TOKENS)
    body = PROMPTS.replace("a crude line drawing of genitals", long_sentence)
    with pytest.raises(PromptsError, match="SEXUAL"):
        parse_prompts(body, THRESHOLDS.keys())


def test_repository_prompt_files_are_valid():
    files = sorted((Path(__file__).resolve().parents[2] / "prompts").glob("*.toml"))
    assert files
    for path in files:
        text = path.read_text(encoding="utf-8")
        assert parse_prompts(text, tomllib.loads(text).keys()), path.name
