# ML moderation server (D-36). Internal network only: compose must not publish its port (SEC-17).
# Build context: ml/  (docker build -f serving/Dockerfile ml)
FROM python:3.12.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    MODELS_LOCK=/app/models.lock MODEL_DIR=/models

RUN groupadd --system ml && useradd --system --gid ml --no-create-home ml
WORKDIR /app/serving
COPY serving/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY models.lock /app/models.lock
COPY serving/app ./app
USER ml

EXPOSE 8000
CMD ["uvicorn", "app.main:build", "--factory", "--host", "0.0.0.0", "--port", "8000"]
