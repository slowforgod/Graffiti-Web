# ml-prep: ML 서버 골격, 모델 잠금 파일, 평가 스크립트

- **상태**: MERGED (PR #24, 2026-10-21)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-21 (결정 필요 1~3은 추천안으로 확정)
- **브랜치**: `feat/ml-prep`
- **선행 명세**: 00

## 1. 왜 (Why)
07b(ML 모더레이션)를 시작할 때 ML 서버, 모델 버전 관리, 평가 방법이 없으면 제로샷 모델 고르기와 카테고리 문장 튜닝(D-37)을 같은 기준으로 비교할 수 없다. 이 명세는 ML 파트가 백엔드 없이 바로 모델을 붙이고 평가할 수 있게 `ml/serving`의 API 골격(API.md §7), `models.lock` 검증(SEC-17), D-39 지표 계산을 먼저 만든다. 실제 모델(CLIP 제로샷)과 OCR 엔진은 ML 파트가 이 골격에 끼운다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-35 | 이미지 분류 + OCR. OCR 엔진은 07b에서 결정, 지금은 "없음" 구현 |
| 결정 | D-36 | 내부망 컨테이너, CPU 추론. Dockerfile만 만들고 compose 추가는 07b |
| 결정 | D-37 | 제로샷 분류기를 끼울 인터페이스, 평가 세트 메타데이터 형식 |
| 결정 | D-39 | 오탐률, 재현율, 응답 시간 p95, 표본 수를 `ml/eval`이 계산 |
| 결정 | D-40 (PROPOSED) | `ml/serving`, `ml/eval`, `ml/models.lock`, 가중치는 git 밖(S3) |
| 불변 조건 | INV-13 | ML 서버는 실패를 4xx/5xx로 분명히 알려 Spring이 "판정 없음"으로 처리하게 함 |
| 보안 | SEC-16 | pip 의존성 전부(전이 포함) 버전 고정, Dependabot pip |
| 보안 | SEC-17 | 내부망 전용, OCR 글자와 이미지는 로그 금지, 가중치는 `models.lock` 체크섬 검증 후 로드 |
| API | `API.md` §7 | `POST /v1/moderate`, `GET /health` 형식 그대로 구현 |

## 3. 범위
**포함**
- `ml/serving` (FastAPI)
  - `POST /v1/moderate`: multipart `image`(PNG), `requestId`. PNG가 아니거나 가로·세로 1024 초과, 바이트 상한 초과면 400. 모델이 없으면 503
  - `GET /health`: 모델이 로드되면 200 `{"status":"UP","modelVersion","ocrVersion"}`, 아니면 503 `{"status":"DOWN",...}`
  - `ImageClassifier`, `OcrEngine` 인터페이스. 판정 라벨(`SAFE`/`FLAGGED`)은 `models.lock`의 카테고리별 임계값으로 서버가 정함(순수 함수)
  - `models.lock` 읽기와 sha256 검증. 체크섬이 다르면 로드하지 않고 503
  - 로그는 `requestId`, 라벨, 응답 시간만. OCR 글자, 점수, 이미지는 남기지 않음
  - `Dockerfile`(비 root, 외부 포트 공개는 compose에서 하지 않음), `requirements.txt`(전이 포함 고정), pytest
- `ml/eval/evaluate.py`: 평가 세트 목록(CSV: 파일, 정답 라벨)으로 서버를 호출하고 D-39 지표를 계산해 출력. 지표 계산은 순수 함수로 분리해 테스트
- `ml/models.lock`: 형식 정의. 아직 모델이 없으므로 `image.version = "none"`
- CI에 `ml` 작업(pytest) 추가, Dependabot pip 추가, `CLAUDE.md` §2 명령어 갱신, `.gitignore`에 평가 이미지 경로
- `ml/README.md`: 모델 붙이는 방법, 평가 세트 만드는 방법, S3 경로 규칙

**제외 (NOT in scope)**
- CLIP 제로샷 분류기 구현과 카테고리 문장 튜닝 → 모델 가중치를 받아야 하는데 이 작업 환경에서 Hugging Face, PyTorch 다운로드가 막혀 있음. ML 파트가 `ImageClassifier`를 구현해서 붙인다
- OCR 엔진 선택과 구현 → 07b (D-35, 한국어 손글씨 정확도 비교 필요)
- S3에서 가중치를 받는 스크립트 → 07b (버킷과 자격 증명 필요)
- compose에 `ml` 서비스 추가, Spring `MlModerationProvider`, `ml_prediction` → 07b
- 평가 세트 그리기 → 팀 작업(05 이후 앱에서 그리거나 임시로 같은 크기 캔버스 도구에서)

## 4. 현재 상태 (검증된 사실)
- `ml/` 디렉터리 없음. `.gitignore`에 `ml/**/models/`, `ml/**/data/`, `ml/**/exports/`, 가중치 확장자가 이미 있음
- CI에 Python 단계 없음

## 5. 설계

```
POST /v1/moderate (multipart)
  └─ read_png(bytes)            400: PNG 아님, 1024 초과, 바이트 초과
  └─ registry.ready?            503: 모델 없음 또는 체크섬 불일치
  └─ classifier.scores(img)     {category: score}
  └─ label = decide(scores, thresholds)   순수: safe 외 카테고리 중 하나라도 임계값 이상이면 FLAGGED
  └─ ocr.read(img)              지금은 NoOcr → ("", 0.0)
  └─ 200 {modelVersion, image{label, scores}, ocr{text, confidence}, latencyMs}
     log: requestId, label, latencyMs   (글자, 점수, 이미지 없음 — SEC-17)

시작: models.lock 읽기 → image.version == "none"이면 모델 없음(503)
                     → 아니면 MODEL_DIR/<file>의 sha256 비교 → 일치하면 분류기 생성
```

| 파일 | 역할 |
|---|---|
| `ml/models.lock` | 모델 버전, S3 경로, 파일 이름, sha256, 카테고리별 임계값 (TOML) |
| `ml/serving/app/main.py` | FastAPI 앱 생성, 엔드포인트 |
| `ml/serving/app/schemas.py` | 응답 스키마 (API.md §7) |
| `ml/serving/app/lock.py` | `models.lock` 파싱, 체크섬 검증 |
| `ml/serving/app/decision.py` | 라벨 결정 (순수) |
| `ml/serving/app/image_input.py` | PNG 검증 |
| `ml/serving/app/models.py` | `ImageClassifier`, `OcrEngine` 프로토콜, `NoOcr`, 모델 레지스트리 |
| `ml/serving/app/settings.py` | 환경변수: `MODELS_LOCK`, `MODEL_DIR`, `MAX_IMAGE_BYTES`, `MAX_IMAGE_SIDE` |
| `ml/eval/evaluate.py`, `ml/eval/metrics.py` | 서버 호출, D-39 지표 |

- 수치: `MAX_IMAGE_SIDE` 1024(API.md §7), `MAX_IMAGE_BYTES` 2MB(D-30 게시 상한과 같게). ML 서버는 Spring 밖이라 `GraffitiProperties`가 아니라 ML 서버 설정 한 곳(`settings.py`)에 둔다
- 결정 필요 1 — 잠금 파일 형식: **추천: TOML** (Python 표준 `tomllib`로 읽어 의존성 없음, 주석 가능). 대안: JSON, YAML
- 결정 필요 2 — Python 버전: **추천: 3.12** (CPU용 PyTorch, OCR 라이브러리 지원이 가장 넓음). 대안: 3.13
- 결정 필요 3 — 모델 없을 때: **추천: 서버는 뜨고 `/health`와 `/v1/moderate`가 503** (Spring은 503을 판정 없음으로 처리, INV-13). 대안: 시작 실패

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 | 테스트 |
|---|---|---|---|
| moderate | PNG가 아닌 파일, 깨진 PNG | 400 `{"error":{"code":"INVALID_IMAGE"}}` | `test_api.py` |
| moderate | 1024보다 큰 이미지, 바이트 상한 초과 | 400 | `test_api.py` |
| moderate | 모델 없음 | 503 | `test_api.py` |
| 시작 | 가중치 체크섬 불일치, 파일 없음 | 로드 거부, 503, 로그에 경로와 버전만 | `test_lock.py` |
| 시작 | `models.lock` 형식 오류 | 시작 실패 (배포 단계에서 바로 드러나게) | `test_lock.py` |
| moderate | 분류기 예외 | 500, 로그에 requestId와 예외 종류만 | `test_api.py` |
| 로그 | OCR 글자, 점수 | 로그에 없음 | `test_api.py` (caplog) |
| eval | 서버 오류, 시간 초과 | 오류로 집계, 지표에서 빠진 수를 같이 출력 | `test_metrics.py` |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1: 모델이 로드된 서버에 PNG를 보내면 API.md §7 형식의 200 응답, `label`은 임계값 기준
- [x] AC-2: 임계값과 같거나 큰 점수가 하나라도 있으면 `FLAGGED`, `safe` 점수는 판정에 쓰지 않음
- [x] AC-3: PNG 아님, 1024 초과, 바이트 초과 → 400
- [x] AC-4: `image.version = "none"`이거나 체크섬이 다르면 `/health`와 `/v1/moderate`가 503
- [x] AC-5 (SEC-17): 요청 처리 로그에 OCR 글자, 점수, requestId 외 사용자 값이 없다
- [x] AC-6: `evaluate.py`의 지표 함수가 D-39의 오탐률, 재현율, p95, 표본 수를 계산하고 기준 통과 여부를 표시
- [x] AC-7 (SEC-16): `requirements.txt`의 모든 패키지가 `==`로 고정, Dependabot pip 등록
- [x] AC-8: CI의 `ml` 작업이 pytest를 돈다
- [x] AC-9: Docker 이미지가 비 root로 뜨고 `/health`가 503(모델 없음)

## 8. 테스트 계획
| 층 | 대상 | 파일 |
|---|---|---|
| 단위 | 라벨 결정, 잠금 파일, PNG 검증, 지표 | `test_decision.py`, `test_lock.py`, `test_image_input.py`, `ml/eval/test_metrics.py` |
| API | 가짜 분류기로 엔드포인트 | `test_api.py` (FastAPI TestClient) |
| QA | Docker 이미지 기동, `/health` 503, `evaluate.py`를 가짜 분류기 서버에 실행 | 수동 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 모델 가중치, 평가 이미지를 git에 넣지 않는다
- 로그에 이미지, OCR 글자, 점수를 남기지 않는다
- API.md §7 형식을 바꾸지 않는다 (바꾸면 Spring과 같은 PR에서)

## 10. 롤백
PR revert. 운영에 연결된 부분이 없다(compose, Spring 연결은 07b).

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-21 · QA 후 커밋 · CRITICAL 0 / INFO 4 · AUTO-FIX 1 · ASK 없음 · PASS
  - AUTO-FIX 1: 테스트에서 가짜 OCR을 넣으려고 클로저를 고치던 방식을 `create_app(..., ocr=)` 인자로 바꿈
  - INFO-1: CLIP 제로샷 분류기는 이 작업 환경에서 Hugging Face와 PyTorch 다운로드가 막혀(프록시 거부) 구현하지 않음. `classifier_factory()`가 None이라 지금 서버는 항상 503. ML 파트가 `ImageClassifier`를 구현해 붙임(`ml/README.md`)
  - INFO-2: PNG 크기는 픽셀을 풀기 전에 헤더로 먼저 확인해서 큰 이미지로 메모리를 쓰게 하지 못함. 업로드는 상한 + 1바이트까지만 읽음
  - INFO-3: 모델 예외는 500 `MODEL_ERROR`로 보내고 로그에는 예외 종류만 남김(메시지에 입력 내용이 섞일 수 있어서)
  - INFO-4: `fastapi`, `pydantic`, `starlette`, `pillow`는 2주 넘게 지난 버전으로 고정. 전이 의존성까지 `requirements.txt`에 고정

## QA 기록 (/gf-qa가 추가)
- 2026-10-21 · 통과 · 버그 0 · PASS (실제 모델은 미검증)
  - 자동: `ml/serving` pytest 36개, `ml/eval` pytest 5개 통과(Python 3.13 로컬, Python 3.12 컨테이너 둘 다). `actionlint` 통과
  - Docker: 이미지 빌드(작업 환경의 TLS 프록시 때문에 QA용으로 CA만 추가한 사본 Dockerfile 사용, 나머지 동일), uid 999 비 root, `/health` 503 `{"status":"DOWN","modelVersion":"none","ocrVersion":"none"}`, `/v1/moderate` 503 `MODEL_NOT_LOADED`, 시작 로그에 "No image model configured"
  - 평가: 가짜 분류기(어두울수록 `sexual` 점수가 높음)로 띄운 서버에 30장(정상 20, 부적절 10 중 2장은 일부러 못 잡게) `evaluate.py` 실행 → 오탐률 0, 재현율 0.8, p95 약 2ms, 표본 수 기준 미달로 `passed: false`. 기대한 값과 같음
  - 미검증: 실제 CLIP 모델 로드와 추론, CI의 `ml` 작업(이 PR의 CI에서 확인)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
