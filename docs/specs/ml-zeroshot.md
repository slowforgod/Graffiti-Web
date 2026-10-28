# ml-zeroshot: OpenCLIP 제로샷 이미지 분류기

- **상태**: APPROVED
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-28 (결정 1~10 확정)
- **브랜치**: `feat/ml-zeroshot`
- **선행 명세**: ml-prep (MERGED, PR #24)

## 1. 왜 (Why)
`ml/serving`은 골격만 있고 `classifier_factory()`가 `None`이라 `/v1/moderate`가 항상 503이다. 이 명세는 OpenCLIP ViT-B-32 제로샷 분류기(D-37)를 그 자리에 끼우고, 점수를 좌우하는 아키텍처와 카테고리 문장까지 `models.lock`으로 잠가서, 모델이나 문장을 바꿀 때마다 같은 평가 세트로 비교할 수 있게 한다. Spring 연결, OCR, 배포는 07b에서 한다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-34 | 자체 모델은 추가 검사층. 기존 검사를 대신하지 않음 |
| 결정 | D-35 | 이미지 분류만. OCR은 07b |
| 결정 | D-36 | CPU 한 대, GPU 전제 코드 금지. 스레드 수를 설정으로 제한 |
| 결정 | D-37 | 제로샷으로 시작, 카테고리를 문장으로 정의, 팀 평가 세트로 비교 |
| 결정 | D-39 | 임계값을 오탐률 ≤ 2%, 재현율 ≥ 70%, p95 ≤ 3초 기준으로 고르고 보고 |
| 결정 | D-40 (PROPOSED) | 가중치는 S3, 저장소에는 `models.lock`만 |
| 불변 조건 | INV-13 | 모델 실패는 500/503으로 분명히 알림. 가짜 SAFE나 0점을 돌려주지 않음 |
| 보안 | SEC-16 | `open_clip_torch`, `torch`와 전이 의존성 전부 `==` 고정 |
| 보안 | SEC-17 | 운영 중 네트워크로 가중치를 받지 않음, 체크섬이 하나라도 다르면 로드 거부, 로그에 점수·이미지 없음 |
| API | `API.md` §7 | 형식 변경 없음. `scores`의 키가 `models.lock` 카테고리와 같아짐 |
| 개발 지침 | `ml/DEVELOPMENT.md` §3~§8 | 모델 후보, 버전 규칙, 구현 규칙, 테스트·평가 방식 |

## 3. 범위
**포함**
- `ClipClassifier`: `ImageClassifier` 구현. 시작할 때 문장 임베딩을 한 번 계산하고, 요청마다 이미지 임베딩만 계산해 카테고리별 0~1 점수를 돌려줌
- `classifier_factory()` 연결. 로컬 safetensors 파일만 받음(`HF_HUB_OFFLINE=1`)
- 입력 전처리: 투명 배경을 흰색으로 합성(지금 `read_png`의 버그 수정, §4), 그림이 있는 영역만 잘라 정사각형으로 맞춤(결정 4)
- 카테고리 문장 파일 `ml/prompts/<version>.toml` 형식과 첫 문장 세트
- `models.lock` 형식 확장(`arch`, `prompts`, `prompts_sha256`, `crop_to_content`)과 `lock.py` 검증
- `settings.py`에 `TORCH_THREADS`
- `ml/eval/build_set.py`: Quick, Draw! 획 데이터를 우리 캔버스(흰 배경, 허용 선 굵기)로 다시 그리고, 혐오 상징을 회전·크기·선 떨림으로 변형해 PNG와 `manifest.csv`를 만듦 (결정 10)
- `ml/eval/calibrate.py`: 보정 세트로 카테고리별 임계값을 고르고, 보고 세트로 D-39 지표를 계산. `manifest.csv`에 그린 사람 익명 ID 열(`drawer`) 추가
- 의존성: CPU 전용 PyTorch 휠, `open_clip_torch` 등 전이 포함 고정. CI `ml` 작업이 같은 휠을 씀
- Dockerfile에 `ml/prompts/` 복사, `HF_HUB_OFFLINE=1`
- 카테고리 이름 바꾸기: 테스트(`conftest.py`, `test_api.py`, `test_decision.py`, `test_lock.py`), `models.lock` 주석, `API.md` §7, `ml/README.md` 예시의 `sexual`·`violence`·`hate_symbol`을 FILTER 이름으로 바꾼다
- 문서 맞추기: paired 방식에는 `safe` 점수가 없으므로 `API.md` §7 응답 예시, `ml/README.md` 예시, `ml/DEVELOPMENT.md` §5의 `safe` 언급을 "`scores` 키 = `models.lock` 임계값 키"로 고친다. `docs/PIPELINE.md` 로드맵에 `ml-zeroshot`을 추가하고 07b 행에서 "CLIP 제로샷 이미지 분류"를 뺀다. `ml/README.md`의 `models.lock` 키 목록과 `manifest.csv` 형식(`drawer` 열)을 갱신한다
- 첫 모델 버전(`clip-zs-YYYY.MM.DD`)의 `models.lock` 항목과 평가 보고서 (평가 세트가 준비된 뒤 같은 PR 또는 후속 PR)

**제외 (NOT in scope)**
- OCR 엔진 → 07b (D-35, 한국어 손글씨 비교 필요)
- Spring `MlModerationProvider`, `DoodleRenderer`, `ml_prediction`, compose `ml` 서비스 → 07b
- S3에서 가중치를 받는 배포 스크립트 → 07b (버킷과 IAM 필요)
- 미세조정, `ml/training/` → TODOS (운영 데이터 이후)
- 선 굵게 하기 같은 추가 전처리 → 평가 결과를 본 뒤 필요하면 새 버전으로

## 4. 현재 상태 (검증된 사실, main `11f1b1d` 기준)
- `ml/serving/app/main.py`: `classifier_factory()`가 `None`을 돌려줌. `build()`는 `create_app(factory=classifier_factory())`
- `ml/serving/app/models.py`: `ImageClassifier.scores(image) -> Mapping[str, float]`, `ClassifierFactory = Callable[[Path], ImageClassifier]`, `Registry.load`가 `verify_file` 통과 후 `factory(path)` 호출
- `ml/serving/app/lock.py`: `[image]`의 `version`, `file`, `sha256`, `uri`, `thresholds`만 읽음. `safe` 임계값은 거부
- `ml/serving/app/image_input.py`: `read_png`가 마지막에 `image.convert("RGB")`를 함. **RGBA PNG의 투명 픽셀은 보통 RGB 값이 (0,0,0)이라 검은 배경이 됨.** 투명 캔버스로 렌더링되면 검은 바탕에 검은 선이 되어 모델이 아무것도 못 봄
- `ml/serving/app/decision.py`: 임계값 이상인 카테고리가 하나라도 있으면 `FLAGGED`
- `ml/serving/requirements.txt`: torch, open_clip 없음
- `ml/eval/evaluate.py`: 응답의 `label`만 모음(점수는 버림). `manifest.csv`는 `file,label`
- `.github/workflows/ci.yml`의 `ml` 작업: Python 3.12, `requirements-dev.txt` 설치 후 pytest
- `ml/prompts/` 없음
- 작업 환경 제약: 클라우드 세션에서는 Hugging Face와 `download.pytorch.org`가 막혀 있음. 구현과 테스트 실행은 현의 Mac 또는 GitHub Actions에서

## 5. 설계

```
시작 (ml/serving)
  read_lock(models.lock)
    ├─ image.version == "none" → 모델 없음 (503, 지금과 같음)
    └─ 가중치 sha256 확인 + prompts 파일 sha256 확인
         └─ 하나라도 다르면 로드 거부 (503)
  ClipClassifier(weights_path, arch, prompts, thresholds 키)
    ├─ open_clip.create_model_and_transforms(arch, pretrained=<로컬 파일>)
    ├─ model.eval(), torch.set_num_threads(TORCH_THREADS)
    ├─ prompts의 카테고리 집합 == thresholds 키 집합? 아니면 로드 거부
    └─ 카테고리마다 positive/negative 문장 임베딩 평균 → 정규화 → 메모리에 보관

POST /v1/moderate
  read_png → 흰 배경 합성 → RGB
  ClipClassifier.scores(img)
    ├─ crop_to_content면: 흰색이 아닌 픽셀의 bbox + 여백 → 정사각형 패딩
    ├─ preprocess(224×224) → 이미지 임베딩 (torch.inference_mode)
    └─ 카테고리 c마다 sigmoid(logit_scale × (cos(img, pos_c) − cos(img, neg_c)))
  decide(scores, thresholds)   (지금 코드 그대로)
```

| 파일 (예정) | 역할 |
|---|---|
| `ml/serving/app/clip_classifier.py` | `ClipClassifier`, 점수 계산 |
| `ml/serving/app/prompts.py` | 문장 파일 파싱과 검증 (순수) |
| `ml/serving/app/preprocess.py` | 흰 배경 합성, 내용 영역 자르기 (순수, PIL만) |
| `ml/serving/app/image_input.py` | 흰 배경 합성을 쓰도록 수정 |
| `ml/serving/app/lock.py` | `arch`, `prompts`, `prompts_sha256`, `crop_to_content` 추가 |
| `ml/serving/app/main.py` | `classifier_factory()`가 `ClipClassifier`를 만드는 함수를 돌려줌 |
| `ml/serving/app/settings.py` | `TORCH_THREADS`, `PROMPTS_DIR` |
| `ml/prompts/clip-zs-YYYY.MM.DD.toml` | 첫 문장 세트 |
| `ml/eval/calibrate.py`, `ml/eval/metrics.py` | 임계값 보정, 보정·보고 세트 분리 |
| `ml/serving/requirements.txt`, `requirements-dev.txt` | CPU 휠 인덱스, 고정 버전 |
| `ml/serving/Dockerfile` | `prompts/` 복사, `HF_HUB_OFFLINE=1` |
| `ml/README.md`, `CLAUDE.md` §2 | 명령어 갱신 |

`models.lock` 예 (키 이름은 결정 2에서 확정):
```toml
[image]
version = "clip-zs-2026.11.01"
arch = "ViT-B-32"
uri = "s3://<bucket>/models/clip-zs-2026.11.01/open_clip_model.safetensors"
file = "open_clip_model.safetensors"
sha256 = "<64 hex>"
prompts = "clip-zs-2026.11.01.toml"
prompts_sha256 = "<64 hex>"
crop_to_content = true

[image.thresholds]
SEXUAL = 0.71
HATE = 0.80
# 폭력은 보지 않음 (결정 9)
```

문장 파일 예:
```toml
[SEXUAL]
positive = ["a crude line drawing of genitals", "a black-and-white doodle of a naked body"]
negative = ["a crude line drawing of a person", "a black-and-white doodle of a body"]
```

### 결정 필요
> 2026-10-28 현: 1~7은 추천안으로 확정. 8~10도 확정.

1. **점수 방식** — 추천: **paired**. 카테고리끼리 독립이라 "하나라도 임계값 이상이면 FLAGGED"와 맞고, 카테고리를 더해도 다른 점수가 바뀌지 않음(`DEVELOPMENT.md` §5). 대안: 전체 softmax
2. **문장 파일 위치와 잠금** — 추천: `ml/prompts/<version>.toml`을 git에 두고 `models.lock`에 이름과 sha256. 문장은 부적절한 내용을 글로만 묘사하므로 git에 둬도 됨. 대안: 가중치처럼 S3
3. **가중치 후보** — 추천: `datacomp_xl_s13b_b90k`와 `laion2b_s34b_b79k`를 같은 문장으로 평가해 좋은 쪽을 첫 버전으로. 둘 다 같은 ViT-B-32라 코드는 같음
4. **내용 영역 자르기** — 추천: `crop_to_content = true`를 기본으로 하되 첫 평가에서 끈 것과 비교하고, 결과를 보고서에 남김. 이유: 1024 캔버스를 224로 줄이면 구석의 작은 그림과 가는 선이 사라짐. 버전 규칙상 이 값도 `models.lock`에 잠금
5. **임계값 고르는 규칙** — 추천: 보정 세트에서 카테고리마다 **오탐률 ≤ 2% ÷ 카테고리 수**를 지키는 가장 낮은 임계값. 카테고리 3개면 각 0.67% 이하라 합쳐도 2%를 넘지 않음. 그 뒤 보고 세트로 D-39 재현율·오탐률을 확인. 대안: F1 최대(D-39와 맞지 않음)
6. **보정·보고 세트 분리** — 추천: 그린 사람(`drawer`) 단위로 나눔. 같은 사람 그림이 양쪽에 섞이면 수치가 부풀려짐. 비율은 보정 60% / 보고 40%
7. **CPU 스레드 수** — 추천: `TORCH_THREADS` 기본 2. EC2 4GB급(vCPU 2)에서 Spring과 나눠 쓰기 위함. 실측 후 조정
8. **카테고리 이름과 폭력** — 결정(2026-10-28, 현): 카테고리 이름은 FILTER.md 이름(`SEXUAL`, `HATE` 등)을 쓴다. 이미지 분류기가 맡을 카테고리와 폭력 그림의 처리는 결정 대기(아래 9)
9. **이미지로 볼 카테고리** — 결정(2026-10-28, 현): `SEXUAL`(성기·성행위 그림), `HATE`(나치 문양 등 혐오 상징)로 시작. `PROFANITY`(가운데 손가락 같은 욕 제스처)는 평가 결과를 본 뒤 새 버전에서 추가. `PERSONAL_INFO`는 글자라서 OCR 몫. **폭력은 A: 자체 모델이 보지 않음**(OpenAI 이미지 검사와 관리자 삭제에 맡김)
10. **평가 세트** — 결정(2026-10-28, 현, D-37 수정): 정상은 Quick, Draw!(CC BY 4.0)를 우리 캔버스로 재렌더링, 부적절은 팀이 그린 것(카테고리마다 30~50개) + 코드로 만든 혐오 상징 변형. Quick, Draw! 그림 한 장 = `drawer` 한 명. 재렌더링·변형 스크립트(`ml/eval/build_set.py`)는 이 명세 범위. 데이터 자체는 git 밖(S3, `ml/eval/sets/`)

### 07b로 넘기는 충돌 (이 명세에서는 다루지 않음)
- 해결(2026-10-28, 현): ML 이미지 카테고리 이름은 FILTER.md 이름을 그대로 쓴다(결정 8). 그래서 07b에서 이름을 바꾸는 대응표가 필요 없다
- 해결(2026-10-28, 현): ENFORCE에서 OCR 글자 검사가 `REVIEW`나 `PROFANITY`면 HELD, 카테고리 2~4면 거부. FILTER.md §5와 같고, `ml/DEVELOPMENT.md` §10을 이에 맞게 고침. ML 장애(시간 초과, 오류)는 여전히 HELD로 만들지 않음

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (결과) | 테스트 |
|---|---|---|---|
| 시작 | 가중치 sha256 불일치, 파일 없음 | 로드 거부, 503, 로그에 버전만 | `test_lock.py` (기존) |
| 시작 | 문장 파일 없음, sha256 불일치 | 로드 거부, 503 | `test_lock.py` |
| 시작 | 문장 카테고리와 임계값 키가 다름, positive/negative 비어 있음 | 로드 거부, 503, 어느 카테고리인지 로그 | `test_prompts.py` |
| 시작 | `arch`가 open_clip에 없는 이름 | 로드 거부, 503 | `test_clip_classifier.py` |
| 시작 | `pretrained`에 파일 대신 태그 이름이 들어가 네트워크 접근 시도 | `HF_HUB_OFFLINE=1`로 즉시 실패, 로드 거부. 코드는 경로만 넘김 | `test_clip_classifier.py` (존재하지 않는 경로 → 실패) |
| 요청 | 투명 배경 PNG | 흰 배경 합성 후 판정 | `test_preprocess.py` |
| 요청 | 완전히 빈 캔버스 (흰색만) | 자르지 않고 전체를 그대로 판정 | `test_preprocess.py` |
| 요청 | 모델 추론 예외 | 500 `MODEL_ERROR`, 로그에 예외 종류만 (기존) | `test_api.py` (기존) |
| 요청 | 추론이 느림 (CPU 경합) | 응답은 늦게라도 정상. Spring이 3초 후 판정 없음 처리(07b). p95는 평가 보고서에 | `calibrate.py` 출력 |
| 평가 | 보정·보고 세트에 같은 `drawer` | 실행 거부 | `test_calibrate.py` |
| 평가 | `drawer` 열 없음 | 실행 거부하고 필요한 열을 안내 | `test_calibrate.py` |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [ ] AC-1: Given 무작위 초기화 ViT-B-32를 저장한 safetensors와 맞는 `models.lock`, When 서버 시작, Then `/health` 200, `modelVersion`이 lock 버전
- [ ] AC-2: Given AC-1 서버, When PNG를 보내면, Then `scores` 키가 `models.lock` 임계값 키와 정확히 같고 값은 모두 0~1 (`safe` 키 없음. API.md §7 예시도 같은 PR에서 맞춤)
- [ ] AC-3: 같은 PNG를 두 번 보내면 같은 점수
- [ ] AC-4: 투명 배경에 검은 선을 그린 RGBA PNG와, 흰 배경에 같은 선을 그린 RGB PNG의 점수가 같음
- [ ] AC-5: 문장 파일 sha256이 다르거나 카테고리가 임계값 키와 다르면 `/health` 503
- [ ] AC-6: `version = "none"`이면 지금처럼 503 (회귀 없음)
- [ ] AC-7: `crop_to_content = true`일 때 캔버스 구석의 작은 그림이 잘린 뒤 정사각형으로 들어감 (전처리 단위 테스트, 결과 이미지의 bbox로 확인)
- [ ] AC-8: `calibrate.py`가 보정 세트에서 결정 5 규칙으로 임계값을 고르고, 보고 세트의 오탐률·재현율·p95·표본 수와 D-39 통과 여부를 출력
- [ ] AC-9 (SEC-16): 새 의존성이 전이 포함 전부 `==` 고정, torch는 CPU 휠
- [ ] AC-10 (SEC-17): 운영 코드에서 `pretrained=`에 태그 이름을 넘기는 경로가 없음. 컨테이너에 `HF_HUB_OFFLINE=1`. 요청 로그에 점수 없음 (기존 caplog 테스트 유지)
- [ ] AC-11: 실제 가중치로 띄운 서버에 팀 평가 세트를 돌린 보고서를 PR에 첨부 (수치만, 이미지 없음)

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | 문장 파일 파싱·검증, lock 확장, 흰 배경 합성, 자르기, paired 점수 계산, 임계값 보정 규칙, 세트 분리 | `test_prompts.py`, `test_lock.py`, `test_preprocess.py`, `test_scoring.py`, `ml/eval/tests/test_calibrate.py` |
| 통합 | 무작위 초기화 ViT-B-32(`pretrained=None`)를 임시 safetensors로 저장 → 체크섬 → 로드 → `/v1/moderate` | `test_clip_classifier.py`, `test_api.py` |
| QA | 실제 가중치 두 후보로 서버 기동, RSS와 1장당 추론 시간 측정(Mac + 가능하면 EC2 동급), 평가 세트 보고서 | 수동 (Mac) |

- 가중치가 없다고 skip하는 테스트는 만들지 않는다(`DEVELOPMENT.md` §7). 무작위 초기화 모델로 skip 없이 돈다
- 무작위 모델은 의미 있는 점수를 내지 않으므로 정확도는 테스트하지 않고, 형식·범위·결정성만 본다

## 9. 구현 에이전트 규칙 (이 명세 전용)
- `pretrained=`에는 항상 검증된 로컬 경로만 넘긴다. 태그 이름 금지
- `.bin`(pickle) 가중치를 받지 않는다. safetensors만
- `torch.autocast("cuda")` 등 GPU 코드 금지. 추론은 `torch.inference_mode()`
- 문장은 영어. 77토큰을 넘기지 않는다
- 평가 이미지와 부적절한 그림을 git, PR, 이슈, 채팅에 올리지 않는다. 보고서에는 수치만
- API.md §7 형식을 바꾸지 않는다
- 클라우드 세션은 torch를 설치할 수 없으므로, 구현 검증은 Mac(Remote Control) 또는 CI에서 한다

## 10. 롤백
`models.lock`의 `image.version`을 `"none"`으로 되돌리면 서버는 503(지금과 같은 상태)이 되고, Spring은 07b 이후에도 판정 없음으로 처리한다(INV-13). 코드는 PR revert. DB 변경 없음.

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
