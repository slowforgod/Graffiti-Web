"""The staging stack keeps the ML server internal (SEC-17, 07b AC-10)."""

from pathlib import Path

import yaml

DEPLOY = Path(__file__).resolve().parents[3] / "deploy"


def test_ml_service_publishes_no_port_and_reads_weights_read_only():
    ml = yaml.safe_load((DEPLOY / "compose.yml").read_text(encoding="utf-8"))["services"]["ml"]

    assert "ports" not in ml
    assert "expose" not in ml
    assert any(v.endswith(":/models:ro") for v in ml["volumes"])
    assert ml["mem_limit"]


def test_nginx_never_routes_to_the_ml_server():
    for conf in (DEPLOY / "nginx").rglob("*"):
        if conf.is_file():
            text = conf.read_text(encoding="utf-8")
            assert "ml:8000" not in text and "/v1/moderate" not in text, conf.name
