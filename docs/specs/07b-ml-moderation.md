# 07b-ml-moderation: OCR, ML 서버 연결, ml_prediction 기록 (SHADOW)

- **상태**: IN_PROGRESS (PR 1)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-28 (결정 1~7 추천대로)
- **브랜치**: `feat/07b-ml-ocr` (PR 1), `feat/07b-ml-wiring` (PR 2)
- **선행 명세**: ml-zeroshot (PR #58), 07-moderation-frame (MERGED)

## 1. 왜 (Why)
ml-zeroshot으로 ML 서버가 그림을 분류할 수 있게 됐지만, Spring이 서버를 부르지 않고 손글씨도 읽지 못한다. 이 명세는 세 가지를 붙인다.
- ML 서버의 OCR
- Spring 쪽 연결: 그림을 PNG로 렌더링, ML 호출, OCR 글자를 텍스트 검사로 넘기기
- 결과 기록(`ml_prediction`)

이렇게 SHADOW로 운영 데이터를 쌓아야 D-39 기준으로 ENFORCE 전환을 판단할 수 있다. 그림으로 쓴 욕설과 개인정보는 OCR 없이는 아무 검사도 받지 않는다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-34 | 자체 ML은 추가 검사층. 기존 검사를 대신하지 않음 |
| 결정 | D-35 | 이미지 분류 + OCR. OCR 글자는 텍스트 검사 경로로 |
| 결정 | D-36 | CPU 한 대, 스레드 제한 |
| 결정 | D-39 | SHADOW 기록으로 ENFORCE 전환 판단 |
| 결정 | D-40 (PROPOSED) | 내부 HTTP, 3초 제한, 가중치는 S3 + `models.lock` |
| 결정 | D-42 (PROPOSED) | 검사 장애 → HELD. 단 ML 경로 장애는 제외(INV-13) |
| 불변 조건 | INV-02 | 렌더링, ML 호출, OCR 글자 검사는 모두 큐 밖 |
| 불변 조건 | INV-13 | SHADOW면 기록만. ML 장애가 게시를 막지 않음 |
| 보안 | SEC-05 | OCR 글자도 LLM 입력 규칙(구분자, 스키마 파싱) 그대로 |
| 보안 | SEC-15 | ML 호출 타임아웃 3초 |
| 보안 | SEC-16 | `easyocr`와 전이 의존성 `==` 고정 |
| 보안 | SEC-17 | ML 서버 내부망 전용, OCR 글자·점수·이미지 로그 금지, 다운로드 금지 |
| 기준 | `FILTER.md` §5 | 그림에서 나온 욕설(카테고리 1)은 HELD, 2~4는 거부 |
| API | `API.md` §7 | 형식 변경 없음. `ocr.text`는 신뢰도 기준을 넘은 글자만 |
| DB | `DB.md` §2 | `ml_prediction` 첫 마이그레이션(V6). `ocr_text_outcome`, `ocr_confidence` |
| 아키텍처 | `ARCHITECTURE.md` §4.7 | 흐름을 이 명세대로 갱신 |

## 3. 범위
PR 두 개로 나눈다. PR 1은 ML 서버만 바꾸므로 백엔드 없이 먼저 머지할 수 있다.

**PR 1: ML 서버 OCR (`ml/`)**
- `EasyOcrEngine`: `OcrEngine` 구현. 한국어+영어(`['ko','en']`), CPU, `download_enabled=False`, `model_storage_directory=MODEL_DIR/ocr`
- 글자 덩어리(chunk)별 신뢰도 게이트: `models.lock [ocr] min_confidence` 미만인 덩어리는 버림. 글자 수 하한 없음(2026-10-28 결정)
- `models.lock [ocr]` 형식 확장과 `lock.py` 검증: `engine`, `langs`, 파일별 `file`·`sha256`·`uri`(검출기, 인식기), `min_confidence`
- OCR 평가 도구 `ml/eval/ocr_eval.py`: 손글씨 세트(`manifest.csv`: `file,text,drawer`)로 글자 정확도(CER)와 신뢰도 분포를 내고 `min_confidence`를 고름
- 합성 손글씨 생성(`build_set.py --handwriting`): 손글씨 폰트(OFL)로 평범한 말, 욕, 전화번호 등을 우리 캔버스처럼 그림
- 의존성 고정, Dockerfile(OCR 가중치 경로)

**PR 2: Spring 연결 (`backend/`, `deploy/`)**
- `DoodleRenderer`: 그림 payload를 Java2D로 PNG 렌더링(흰 배경, bbox 크기, 최대 1024). 07의 `openai-image`도 같은 것을 씀
- `MlModerationProvider`: `POST /v1/moderate` 호출(multipart, 3초), 응답을 `MlAnswer`(label, scores, ocrText, ocrConfidence, latency)로 엄격히 파싱. 4xx·5xx·시간 초과 → `MlOutcome.TIMEOUT|ERROR`
- `MlAnalysis.analyzeDrawing(payload, requestId)`: 렌더링 → ML 호출 → OCR 글자가 있으면 `checks.forKind("TEXT")`로 검사 → 결과 하나로 묶음
- 실행 시점
  - SHADOW: 게시 응답 뒤 백그라운드에서 `analyzeDrawing` 실행. 게시 결과와 응답 시간에 영향 없음(2026-10-28 결정)
  - ENFORCE: 판정 전에 `analyzeDrawing`을 부르고 결과를 `VerdictCombiner`에 넣음
- ENFORCE 합성 규칙(2026-10-28 결정)
  - 이미지 FLAGGED → 거부
  - OCR 글자 검사가 BLOCK이고 카테고리 2~4 → 거부
  - BLOCK이면서 PROFANITY이거나 REVIEW → HELD
  - ML 경로 장애 → 판정에서 빠짐(HELD 아님)
- `ml_prediction` V6 마이그레이션, `MlPredictionRecorder`(게시 트랜잭션과 분리, 실패해도 게시 결과 불변)
- 기존 `ml` 스텁 검사(`ModerationChecks.ml()`)를 이 경로로 교체
- `deploy/compose.yml`에 `ml` 서비스: 포트 비공개, `/models` 읽기 전용 볼륨, `mem_limit`
- 문서: `API.md` §7, `DB.md`, `ARCHITECTURE.md` §4.7, `deploy/README.md`(가중치 배치 절차)

**제외 (NOT in scope)**
- 검사기 자체(금칙어 목록, ChatKHU, OpenAI): 07 명세. 07 전에는 OCR 글자 검사가 스텁이라 `ocr_text_outcome = SKIPPED`로 기록됨
- S3에서 가중치를 자동으로 받는 스크립트: 버킷과 IAM이 정해진 뒤 별도 작업. 그 전에는 `deploy/README.md` 절차대로 사람이 `/opt/graffiti/models`에 둠
- PROFANITY 제스처 이미지 분류: ml-zeroshot 다음 문장 버전
- 관리자 화면에 ML 결과 표시, `ml_prediction` 통계 화면: TODOS
- ENFORCE 전환: D-39 기준 + 팀 승인 뒤 설정값으로만

## 4. 현재 상태 (검증된 사실, main `3569df3` 이후 + PR #58 기준)
- `ml/serving/app/models.py`: `OcrEngine.read(image) -> (text, confidence)` 프로토콜과 `NoOcr`(빈 글자). `main.py`가 `registry.ocr.read`를 부르고 응답 `ocr`에 넣음
- `ml/models.lock [ocr]`: `version = "none"`만 있음
- `ModerationService.moderate`: 그림 검사 뒤 `checks.ml()`을 같은 요청 안에서 동기 실행. `ml`은 `StubCheck`(SKIPPED). 결과는 `moderation_detail`의 `ml` 항목에만 남음
- `ModerationInput`: 그림은 payload JSON만 가짐. **PNG 렌더러가 없음**(`DoodleRenderer`는 ARCHITECTURE §4.1에만 있고 코드에 없음)
- `ml_prediction`: `DB.md`에만 있고 마이그레이션 없음(V1~V5)
- `application.yml`: `graffiti.moderation.ml.mode: SHADOW`, `base-url: http://ml:8000`, `timeout: 3s`
- `deploy/compose.yml`: `web`, `app`(mem_limit 1g), `mysql`(768m), `certbot`. `ml` 없음
- EasyOCR 1.7.2(PyPI 최신, 2024-09)에서 확인한 것
  - `Reader(lang_list, gpu, model_storage_directory, download_enabled)`. `download_enabled=False`면 파일이 없을 때 다운로드 대신 예외
  - 가중치는 `craft_mlt_25k.pth`(검출)와 `korean_g2.pth`(인식). **`torch.load(..., weights_only=False)`로 읽음 = pickle**
  - `readtext`는 덩어리마다 `(bbox, text, confidence)`를 돌려줌
  - 의존성: torch, torchvision, opencv-python-headless, scipy, scikit-image, python-bidi, PyYAML, Shapely, pyclipper, ninja

## 5. 설계

```
POST /api/.../doodles (그림)
  ① ReservePost (큐)
  ② ModerationService.moderate  ── 큐 밖
       텍스트·그림 검사 (07)            ─┐
       mode=ENFORCE면 analyzeDrawing ────┤→ VerdictCombiner → PASSED/HELD/REJECTED/UNCHECKED
  ③ PublishDoodle / WithholdDoodle (큐) → doodle.id
  ④ 201 응답
  ⑤ mode=SHADOW면 백그라운드: analyzeDrawing → MlPredictionRecorder(doodle.id)
     mode=ENFORCE면 ②의 결과를 ③ 뒤에 기록

analyzeDrawing(payload, requestId)        (큐 밖, 가상 스레드)
  DoodleRenderer.render(payload) → PNG
  MlModerationProvider.moderate(PNG)  ── 3초
    ├─ 실패 → MlAnswer(outcome=TIMEOUT|ERROR)
    └─ OK → label, scores, ocr.text, ocr.confidence
  ocr.text가 비어 있지 않으면 checks.forKind("TEXT")를 같은 규칙으로 실행 (금칙어 → ChatKHU → OpenAI)
    → ocr_text_outcome = PASS | REVIEW | BLOCK | ERROR | SKIPPED, 카테고리
  ocr.text가 비면 ocr_text_outcome = NONE

ML 서버 /v1/moderate
  read_png → classifier.scores (ml-zeroshot)
  EasyOcrEngine.read: readtext → 덩어리마다 confidence ≥ min_confidence만 남김
    → text = 남은 덩어리를 읽는 순서(위→아래, 왼→오)로 공백 연결
    → confidence = 남은 덩어리 신뢰도 평균 (없으면 0, text "")
```

`ml_prediction` (V6, `DB.md` 갱신)
```sql
ocr_text_outcome  ENUM('NONE','PASS','REVIEW','BLOCK','ERROR','SKIPPED') NULL, -- outcome=OK일 때
ocr_text_category VARCHAR(40)  NULL,   -- FILTER 카테고리 (BLOCK, REVIEW일 때)
ocr_confidence    DECIMAL(4,3) NULL,   -- ML 서버가 돌려준 평균 신뢰도
-- ocr_text_flagged 삭제 (아직 마이그레이션된 적 없음)
```

`models.lock [ocr]` 예:
```toml
[ocr]
version = "easyocr-1.7.2-ko"
engine = "easyocr"
langs = ["ko", "en"]
min_confidence = 0.4          # 평가로 정함
[ocr.detector]
file = "craft_mlt_25k.pth"
sha256 = "<64 hex>"
uri = "s3://<bucket>/models/easyocr-1.7.2-ko/craft_mlt_25k.pth"
[ocr.recognizer]
file = "korean_g2.pth"
sha256 = "<64 hex>"
uri = "s3://<bucket>/models/easyocr-1.7.2-ko/korean_g2.pth"
```

| 파일 (예정) | 역할 |
|---|---|
| `ml/serving/app/ocr.py` | `EasyOcrEngine`, 덩어리 게이트·정렬(순수 함수 분리) |
| `ml/serving/app/lock.py`, `models.py`, `main.py` | `[ocr]` 검증, 체크섬, 로드 실패 → 503 |
| `ml/eval/ocr_eval.py`, `build_set.py` | OCR 평가, 합성 손글씨 |
| `backend/.../moderation/DoodleRenderer.java` | Java2D PNG 렌더링 (순수, Spring 의존 없음) |
| `backend/.../moderation/MlModerationProvider.java` | HTTP 호출, 응답 파싱 |
| `backend/.../moderation/MlAnalysis.java` | `analyzeDrawing`, OCR 글자 검사 |
| `backend/.../moderation/MlPredictionRecorder.java` | `ml_prediction` INSERT |
| `backend/.../moderation/VerdictCombiner.java` | ENFORCE의 ML 결과 합성 |
| `backend/.../doodle/PublishService.java` | 게시 뒤 SHADOW 분석 예약, ENFORCE 기록 |
| `V6__ml_prediction.sql` | 테이블 |
| `deploy/compose.yml`, `deploy/README.md` | `ml` 서비스, 가중치 배치 |

설정값(`GraffitiProperties`, INV-10): `graffiti.moderation.ml.mode|base-url|timeout`(기존), `graffiti.moderation.ml.max-in-flight`(SHADOW 백그라운드 동시 실행 상한, 넘으면 건너뛰고 기록 안 함), `graffiti.moderation.ml.render-max-side`(1024)

### 결정
> 2026-10-28 현: 1~7 모두 추천대로 확정.

1. **OCR 엔진**: 추천 **EasyOCR 1.7.2**
   - 장점: torch를 CLIP과 같이 써서 이미지가 덜 커짐
   - 장점: 덩어리별 신뢰도를 돌려줌
   - 단점: 마지막 릴리스가 2024-09로, 업데이트가 뜸함

   대안 PaddleOCR(한국어 품질 평이 좋음)은 PaddlePaddle 프레임워크가 추가로 필요하다. 손글씨 평가에서 EasyOCR의 글자 오류율이 너무 높을 때만 비교한다.
2. **pickle 가중치**: EasyOCR은 `.pth`를 `weights_only=False`로 읽어서, 파일이 바뀌면 코드가 실행될 수 있다. 추천 **A: 공식 GitHub 릴리스 파일을 Mac에서 받아 sha256으로 잠그고, 체크섬이 맞을 때만 읽음**. 패키지 코드를 믿는 것과 같은 수준의 신뢰다. 대안 B: Mac에서 safetensors로 변환하고 EasyOCR 내부 모듈에 직접 state_dict를 넣음. 더 안전하지만 EasyOCR 내부 구조에 묶여 업그레이드가 어려움
3. **`min_confidence` 초깃값**: 추천 **손글씨 평가 세트에서 "평범한 글자의 덩어리 90%가 남는 값"**으로 시작하고, 욕·개인정보 재현율과 함께 보고서에 남김
4. **손글씨 평가 세트**: 추천 **팀이 캔버스에 쓴 단어 60개 이상**(평범한 말 30, 욕 15, 전화번호·학번 15, 2명 이상) **+ 합성 손글씨**. 합성만으로는 점수가 부풀려지므로 보고서에 둘을 나눠 적음
5. **DoodleRenderer 위치**: 추천 **이 명세(PR 2)에서 만들고 07의 `openai-image`가 재사용**. 07이 먼저 진행되면 07에서 만들고 이 명세는 그것을 씀
6. **SHADOW 백그라운드 상한**: 추천 `max-in-flight = 4`. 몰릴 때 ML 서버 대기열이 쌓이지 않게 넘치는 건 기록하지 않음(평가 표본이 조금 줄 뿐 게시에는 영향 없음)
7. **`ml` 컨테이너 메모리**: 추천 `mem_limit: 1536m`(CLIP ViT-B-32 + EasyOCR 한국어, CPU). EC2 4GB(t3.medium)가 전제. 실측 후 조정

확정된 것 (2026-10-28, 현)
- SHADOW에서 ML과 OCR 글자 검사는 게시 응답 뒤에 실행. ENFORCE는 판정 전에 같은 `analyzeDrawing` 호출
- OCR 게이트는 덩어리별 신뢰도만, `models.lock [ocr]`에 기준값. 글자 수 하한 없음
- ENFORCE에서 OCR 글자 검사 REVIEW 또는 PROFANITY → HELD, 카테고리 2~4 → 거부. ML 장애는 HELD 아님
- `ml_prediction`: `ocr_text_flagged` → `ocr_text_outcome`(결과 값), `ocr_confidence` 추가

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (결과) | 테스트 |
|---|---|---|---|
| ML 서버 시작 | OCR 가중치 없음·sha256 불일치 | 로드 거부, 503(이미지 분류도 같이). 로그에 버전만 | `test_lock.py`, `test_api.py` |
| ML 서버 시작 | `download_enabled=False`인데 파일 이름이 lock과 다름 | EasyOCR 예외 → 503 | `test_ocr.py` |
| ML 서버 요청 | 글자 없는 그림 | `ocr.text = ""`, confidence 0 | `test_ocr.py` |
| ML 서버 요청 | 모든 덩어리가 기준 미만 | `ocr.text = ""` | `test_ocr.py` (순수 게이트) |
| Spring 렌더링 | payload가 잘못돼 렌더링 예외 | ML 경로만 ERROR로 기록, 게시 결과 불변 | `DoodleRendererTest`, `MlAnalysisTest` |
| Spring → ML | 3초 초과, 4xx, 5xx, 연결 거부 | `outcome=TIMEOUT|ERROR` 기록. SHADOW·ENFORCE 모두 게시 결과 불변(INV-13) | `MlModerationProviderTest`(MockWebServer) |
| Spring → ML | 응답 JSON이 스키마와 다름(점수 범위 밖, 키 누락) | ERROR로 처리, 원문 로그 금지 | `MlModerationProviderTest` |
| OCR 글자 검사 | ChatKHU·OpenAI 장애 | `ocr_text_outcome=ERROR`. ENFORCE에서도 HELD 아님(ML 경로 장애) | `MlAnalysisTest` |
| SHADOW 백그라운드 | 동시 실행 상한 초과 | 건너뜀, 카운터 로그(내용 없음) | `MlAnalysisTest` |
| 기록 | OCR 글자가 500자(`ocr_text` 열)를 넘음 | 500자로 잘라 저장, 검사는 전체 글자로 | `MlPredictionRecorderIT` |
| 기록 | `ml_prediction` INSERT 실패 | 경고 로그(doodle id만), 게시 결과 불변 | `MlPredictionRecorderIT` |
| 기록 | 백그라운드 분석 중 앱 종료 | 그 건은 기록 안 됨(평가 표본 손실만) | 수용 (문서화) |
| 로그 | 예외 메시지에 OCR 글자 포함 | 예외 종류만 로그(SEC-17) | caplog / 로그 단언 테스트 |

critical gap: 0

## 7. 수용 기준
- [ ] AC-1: Given 실제 형식의 `[ocr]` lock과 임시 가중치(테스트용 무작위 초기화 모델 저장), When 서버 시작, Then `/health` 200, `ocrVersion`이 lock 버전
- [ ] AC-2: 덩어리 `[("안녕", 0.9), ("xx", 0.2)]`, `min_confidence=0.4` → `text="안녕"`, confidence 0.9 (순수 함수)
- [ ] AC-3: OCR 가중치 sha256이 다르면 `/health` 503
- [ ] AC-4: SHADOW에서 ML 서버가 503이거나 3초를 넘겨도 게시 응답과 doodle 상태가 ML 없는 경우와 같고, `ml_prediction`에 `TIMEOUT|ERROR` 한 행
- [ ] AC-5: SHADOW에서 이미지 FLAGGED여도 doodle은 PASSED/UNCHECKED 그대로, `ml_prediction`에 `FLAGGED`와 점수 기록
- [ ] AC-6: ENFORCE에서 이미지 FLAGGED → REJECTED. OCR 글자 검사 BLOCK(SEXUAL) → REJECTED. BLOCK(PROFANITY) 또는 REVIEW → HELD. ML 장애 → 다른 검사 결과 그대로
- [ ] AC-7: OCR 글자가 비면 텍스트 검사기를 부르지 않음(호출 0회)
- [ ] AC-8: `DoodleRenderer`가 흰 배경 RGB PNG를 bbox 크기(최대 1024)로 만들고, 같은 payload는 같은 바이트
- [ ] AC-9: `ml_prediction`에 작성자 정보 없음(doodle_id만), 로그에 OCR 글자·점수 없음
- [ ] AC-10: compose의 `ml` 서비스에 `ports:` 없음, nginx 설정에 `ml` 경로 없음(SEC-17)
- [ ] AC-11: 손글씨 평가 보고서(글자 오류율, 신뢰도 분포, 고른 `min_confidence`, 욕·개인정보 재현율)를 PR 1에 첨부(수치만)

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 (Python) | 덩어리 게이트·정렬, `[ocr]` lock 검증 | `test_ocr.py`, `test_lock.py` |
| 통합 (Python) | 무작위 초기화 CRAFT·인식기를 저장해 실제 EasyOCR 경로로 로드 → `/v1/moderate` | `test_ocr.py`, `test_api.py` |
| 단위 (Java) | `DoodleRenderer`, `VerdictCombiner` ML 합성, 응답 파싱 | `DoodleRendererTest`, `VerdictCombinerTest`, `MlModerationProviderTest` |
| 통합 (Java) | 게시 → SHADOW 백그라운드 → `ml_prediction` (MySQL Testcontainers, ML 서버는 MockWebServer) | `MlShadowIT` |
| QA | Mac에서 실제 가중치 ML 서버 + 백엔드로 손글씨 낙서 게시 → 기록 확인 | 수동 |

- 실제 가중치가 없다고 skip하는 테스트는 만들지 않는다(무작위 초기화로 대신)

## 9. 구현 에이전트 규칙 (이 명세 전용)
- `download_enabled=False`를 항상 넘긴다. 운영 코드에서 다운로드 경로가 없어야 함
- OCR 글자는 로그·예외 메시지·`moderation_detail`에 넣지 않는다. `ml_prediction.ocr_text`에만 저장
- ML 모드를 코드에서 바꾸지 않는다. 기본은 SHADOW 그대로
- 렌더링과 ML 호출을 `PageCommandProcessor` 큐 안에서 하지 않는다(INV-02)
- 손글씨 평가 이미지(욕, 개인정보 포함)는 git·PR·채팅에 올리지 않는다. 보고서는 수치만
- API.md §7 형식을 바꾸지 않는다

## 10. 롤백
- ML 서버: `models.lock [ocr] version = "none"`으로 되돌리면 OCR 없이(`NoOcr`) 이미지 분류만 동작
- Spring: `graffiti.moderation.ml.mode: OFF`면 ML 호출과 기록이 모두 멈춤
- V6 테이블은 남겨 둠(INV-07의 취지대로 기록 보존)

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
