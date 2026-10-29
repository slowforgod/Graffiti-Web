# ml 개발 지침

`ml/`에서 작업할 때 지킬 규칙과 방향을 모은 문서입니다. 사용법(서버 실행, 평가 명령)은 `ml/README.md`에 있습니다.
이 문서는 결정 문서를 요약할 뿐, 새 결정을 만들지 않습니다. 이 문서와 원문(`docs/DECISIONS.md`, `docs/ARCHITECTURE.md`, `docs/API.md`, `docs/SECURITY.md`)이 다르면 **원문이 맞습니다**. 원문에 없거나 모호하면 추측하지 말고 멈춰서 묻습니다(`NEEDS_CONTEXT`).

## 1. ML 파트의 역할과 방향

- **추가 검사층**입니다(D-34). 자체 모델은 기존 검사(금칙어, ChatKHU, OpenAI)를 대신하지 않고, OpenAI가 약한 영역(손글씨 한국어 욕설, 학교 맥락의 부적절한 그림)을 보완합니다.
- 범위는 **그림 doodle만**입니다(D-35). 그림의 시각적 내용은 이미지 분류, 그림 속 손글씨는 OCR로 글자를 뽑아 텍스트 검사 경로로 넘깁니다. 타이핑한 텍스트 doodle은 ML을 거치지 않습니다.
- **SHADOW로 시작합니다**(D-34, INV-13). 판정은 `ml_prediction`에 기록만 하고 게시 결과를 바꾸지 않습니다. ENFORCE 전환은 D-39 기준을 넘기고 팀이 승인했을 때 설정값으로만 합니다. 코드에서 모드를 바꾸지 않습니다.
- **제로샷 → 미세조정** 순서입니다(D-37). 출시 시점에는 학습 데이터가 없으므로 OpenCLIP 제로샷으로 시작하고, 미세조정은 운영 데이터가 쌓인 뒤에 합니다(TODOS).
- CPU 한 대에서 돕니다(D-36). 같은 EC2의 내부망 컨테이너이고, GPU를 전제로 한 코드는 쓰지 않습니다.

### 단계

| 단계 | 내용 | 상태 |
|---|---|---|
| `ml-prep` | 서버 골격, `models.lock` 검증, 평가 스크립트 | MERGED |
| `ml-zeroshot` | OpenCLIP 제로샷 분류기, `models.lock` 형식 확장(§4), 첫 모델 버전과 평가 보고서 | APPROVED (`docs/specs/ml-zeroshot.md`) |
| `07b-ml-moderation` | OCR, compose의 `ml` 서비스, Spring `MlModerationProvider`, `ml_prediction`, SHADOW 운영 | 07 이후 |
| 미세조정, ENFORCE 전환, 서버 분리 | 운영 데이터와 팀 승인 이후 | TODOS |

`ml-zeroshot`은 07b와 별개인 작은 명세로 둡니다. PIPELINE의 "ML 서버는 07과 병행 가능"을 따르면서 "APPROVED 명세의 범위만 구현" 규칙도 지키기 위해서입니다. 명세가 APPROVED되기 전에는 `ml/serving` 코드에 분류기를 넣지 않습니다.

## 2. 작업 절차

프로젝트 공통 규칙(`CLAUDE.md` §6)을 그대로 따릅니다. ML에서 특히 신경 쓸 것만 적습니다.

1. **명세 먼저**: `/gf-spec`으로 쓰고 APPROVED된 뒤 `/gf-implement`로 진행합니다.
2. **테스트 먼저**: 기능은 TDD로, 버그는 회귀 테스트로 실패를 먼저 확인합니다(§7).
3. **커밋**: 논리적 변경 하나에 커밋 하나, 파일을 지정해서 stage, Conventional Commits(`feat(ml): ...`). main에 직접 push하지 않습니다.
4. **같은 PR에서 함께 고치는 것**
   - ML 요청·응답 스키마 → `ml/serving/app/schemas.py`, Spring `MlModerationProvider`, `docs/API.md` §7
   - `models.lock` 버전 → 평가 보고서(§8)
   - 흐름이나 구성 변경 → `docs/ARCHITECTURE.md` §4.7
   - 명령어 변경 → `CLAUDE.md` §2, `ml/README.md`
5. **PR 전**: 전체 검사(`CLAUDE.md` §2)를 실제로 돌리고 결과(통과·실패·스킵 수)를 붙입니다. `ml/serving`을 바꾼 PR은 배포 전에 `/gf-security`를 통과해야 합니다(PIPELINE).

## 3. 모델 선택: OpenCLIP ViT-B-32

- 라이브러리는 [`mlfoundations/open_clip`](https://github.com/mlfoundations/open_clip)이고, 패키지 이름은 `open_clip_torch`입니다. 추론에는 `[training]` 옵션이 필요 없습니다.
- 가중치 후보는 아래 두 개입니다. 아키텍처가 같아서 메모리와 속도도 같습니다.

  | pretrained 태그 | HF 저장소 | 38개 데이터셋 평균 / ImageNet |
  |---|---|---|
  | `datacomp_xl_s13b_b90k` | `laion/CLIP-ViT-B-32-DataComp.XL-s13B-b90K` | 0.580 / 69.2% |
  | `laion2b_s34b_b79k` | `laion/CLIP-ViT-B-32-laion2B-s34B-b79K` | 0.569 / 66.6% |

  이 수치는 사진 기준입니다. 우리 입력은 마우스로 그린 선 그림이므로, **팀 평가 세트(D-37)로 두 후보를 같은 프롬프트로 비교해서** 고릅니다.
- **라이선스**: open_clip 코드와 두 가중치 모두 MIT입니다. pip로 설치해서 쓰기만 하면 따로 할 일은 없습니다. open_clip 코드를 일부 복사해 넣으면 그 파일 위에 출처와 MIT 고지를 남깁니다.
- **알려진 위험: 모델 카드 권고**: 두 가중치의 모델 카드는 OpenAI CLIP 카드를 따라 "배포된 사용은 현재 범위 밖"이라고 적고 있습니다. 라이선스 조건이 아니라 만든 쪽의 권고입니다. 근거는 CLIP 성능이 분류 체계에 따라 크게 흔들린다는 점이고, 카드는 고정된 분류 체계로 도메인 안에서 충분히 테스트하라고 권합니다. 우리는 이렇게 대응합니다.
  - 카테고리 문장을 고정하고 버전으로 관리합니다(§4).
  - 모델이나 문장을 바꿀 때마다 같은 평가 세트로 비교합니다(D-37).
  - SHADOW에서는 판정이 게시에 영향을 주지 않습니다(INV-13).
  - D-39 기준을 넘기고 팀이 승인해야 ENFORCE로 바꿉니다.

  이 위험을 `DECISIONS.md` D-37에 기록하는 일은 팀 합의 후 사람이 합니다(§11).

## 4. `models.lock`과 모델 버전

제로샷 점수는 가중치뿐 아니라 **아키텍처와 카테고리 문장**에 따라서도 달라집니다. 그래서 셋을 모두 `models.lock`에 묶습니다. 지금 형식에는 아키텍처와 문장이 없으므로, 형식 확장은 `ml-zeroshot` 명세의 범위에서 합니다.

- **잠그는 것**: `version`, `arch`(예: `ViT-B-32`), 가중치 `uri`·`file`·`sha256`, 카테고리 문장 파일의 이름과 `sha256`, `[image.thresholds]`
  - 키 이름과 문장 파일의 위치·형식은 명세에서 확정합니다. 추천 위치는 git에 두는 `ml/prompts/<version>.toml`이고, Dockerfile이 이 파일을 이미지에 복사해야 합니다.
- **버전 규칙**: 가중치, 아키텍처, 문장, 임계값 중 **하나라도 바뀌면 새 `version`**을 만들고, 같은 PR에 평가 보고서를 붙입니다(D-39). 이름은 API 예시처럼 `clip-zs-YYYY.MM.DD` 형식을 씁니다.
- 서버는 체크섬이 하나라도 맞지 않으면 모델을 불러오지 않고 503을 돌려줍니다(SEC-17, 지금 `Registry.load`와 같은 동작).
- 가중치는 S3 `s3://<bucket>/models/<version>/<file>`에만 둡니다. git에는 넣지 않습니다(`.gitignore`가 `*.safetensors`, `*.pt` 등을 막고 있음).

## 5. 분류기 구현 규칙

`ImageClassifier` 프로토콜(`ml/serving/app/models.py`)을 구현하고, `classifier_factory()`(`main.py`)가 그 생성 함수를 돌려주게 합니다. 생성 함수는 체크섬 검증을 통과한 로컬 파일 경로만 받습니다.

- **네트워크 금지**: `pretrained=`에는 항상 로컬 파일 경로를 넘깁니다. 태그 이름을 넘기면 Hugging Face에서 내려받으므로 운영 코드에서는 쓰지 않습니다. 컨테이너에는 `HF_HUB_OFFLINE=1`을 둡니다. ML 서버는 내부망 전용입니다(SEC-17).
- **가중치 형식**: HF 저장소의 `open_clip_model.safetensors`를 씁니다. `.bin`은 pickle이라 파일을 불러올 때 코드가 실행될 수 있습니다.
  - v3.3.0 `factory.py`에서 확인한 동작: `pretrained=`가 실제 파일이면 그 파일을 불러오고, 이름이 `.safetensors`로 끝나면 `safetensors.torch.load_file`로 읽습니다. 그 밖의 파일은 `torch.load(weights_only=True)`가 기본입니다.
  - 버전을 올릴 때는 이 동작이 그대로인지 다시 확인하고, 로드 테스트(§7)로 고정합니다.
- **추론 모드**: 생성 직후 `model.eval()`을 호출하고(모델이 train 모드로 생성됨), 추론은 `torch.inference_mode()` 안에서 합니다. README 예제의 `torch.autocast("cuda")`는 쓰지 않습니다(CPU 전용).
- **텍스트 임베딩은 시작할 때 한 번만**: 카테고리 문장은 버전 안에서 고정이므로 서버가 시작할 때 미리 계산해 둡니다. 요청마다 계산하는 것은 이미지 쪽뿐입니다.
  - `open_clip.zero_shot_classifier.build_zero_shot_classifier()`가 문장 여러 개를 임베딩하고 평균 낸 뒤 정규화합니다. import해서 쓰고, 코드를 옮겨 쓰면 MIT 고지를 남깁니다.
  - 문장은 토큰 77개(`model_configs/ViT-B-32.json`의 `context_length`)를 넘으면 잘립니다. 짧게 씁니다.
- **카테고리 문장**
  - **영어로** 씁니다. 모델이 영어로만 학습됐고, 모델 카드도 영어 외 사용을 권하지 않습니다.
  - "a crude line drawing of ...", "a black-and-white doodle of ..."처럼 선 그림이라는 도메인을 문장에 넣습니다.
  - 카테고리마다 여러 문장을 써서 평균을 냅니다(프롬프트 앙상블). 템플릿 예시는 open_clip의 `zero_shot_metadata.py`에 있습니다(예: "a rendering of a {}", "graffiti of a {}").
  - 카테고리마다 정상 쪽(`negative`) 문장도 여러 개 둡니다. 가장 비슷한 평범한 낙서를 적어야 오탐이 줄어듭니다(D-39 오탐 ≤ 2%).
- **점수 형식**: 카테고리 이름 → 0~1 점수의 dict입니다. 카테고리 이름은 `docs/FILTER.md`의 카테고리 이름(`PROFANITY`, `HATE`, `SEXUAL`, `PERSONAL_INFO`)을 그대로 쓰고(2026-10-28 결정), `models.lock`의 임계값 키와 같아야 합니다. 이미지로 판별할 수 있는 카테고리만 둡니다. 폭력처럼 FILTER에 없는 내용의 처리는 `ml-zeroshot` 명세의 결정을 따릅니다. paired 방식에는 `safe` 점수가 없습니다.
  - 점수 방식은 `ml-zeroshot` 명세 결정 1에서 **paired**로 정했습니다(2026-10-28). 라벨 결정이 "하나라도 임계값 이상이면 FLAGGED"라서 방식에 따라 임계값 의미가 달라집니다. 후보는 아래 둘입니다.
    - **paired (추천 후보)**: 카테고리마다 positive 문장(부적절한 그림)과 negative 문장(평범한 낙서)을 짝지어 `sigmoid(logit_scale × (cos(이미지, positive) − cos(이미지, negative)))`로 계산합니다. 카테고리끼리 서로 독립이라 "하나라도 넘으면 FLAGGED"와 잘 맞고, 점수가 그대로 0~1입니다. open_clip main의 `scripts/multilabel_zeroshot.py`(`score_features`의 `paired`)와 `docs/multilabel_zeroshot.md`가 같은 방식입니다.
    - **softmax**: 모든 카테고리와 `safe`를 한 번에 softmax합니다. README 예제 방식입니다. 카테고리를 하나 추가하면 다른 카테고리의 점수까지 바뀝니다.
  - paired를 고르면 문장 파일에 카테고리마다 `positive`와 `negative` 목록이 있어야 합니다. 서버는 시작할 때 문장 파일의 카테고리가 `[image.thresholds]`와 정확히 같고 목록이 비어 있지 않은지 검사하고, 다르면 로드하지 않습니다(`multilabel_zeroshot.py`의 `validate_prompts`가 같은 검사).
- **입력**: `DoodleRenderer`가 만든 PNG(최대 1024×1024)입니다. 투명 배경이 있으면 흰 배경에 합성한 뒤 RGB로 바꾸고, 그다음 open_clip의 `preprocess`를 적용합니다.
- **수치 한곳에**: 서버의 튜닝 값은 `settings.py`(환경변수)에, 모델별 값(임계값)은 `models.lock`에 둡니다. 코드에 숫자를 흩어 두지 않습니다(INV-10의 ML 서버판).
- **예외**: 모델 오류는 지금처럼 `main.py`가 500 `MODEL_ERROR`로 바꿉니다. 분류기 안에서 예외를 삼키고 가짜 점수를 돌려주지 않습니다. 빈 `except`는 금지입니다.

## 6. 의존성과 실행 환경

- **전부 버전 고정**(SEC-16): `open_clip_torch`, `torch`, `torchvision`과 전이 의존성(`timm`, `ftfy`, `regex`, `huggingface_hub`, `safetensors`, `tqdm` 등)까지 `requirements.txt`에 `==`로 적습니다.
- **CPU 전용 PyTorch**: 기본 휠은 CUDA를 포함해 매우 큽니다. PyTorch CPU 인덱스(`https://download.pytorch.org/whl/cpu`)의 휠을 쓰고, 그 방법을 `requirements.txt`나 Dockerfile에 명시합니다. CI와 Docker가 같은 휠을 쓰게 합니다.
- **Python 3.12**, 컨테이너는 `ml/serving/Dockerfile` 기준입니다. 비root 사용자로 실행하는 설정은 유지합니다.
- **자원**: ViT-B-32 가중치는 fp32 기준 약 600MB입니다. 실제 메모리 사용량(RSS)과 1장당 추론 시간을 EC2와 같은 사양에서 재서 명세에 적습니다. compose의 `mem_limit`(07b)과 D-39의 "p95 3초 이내"가 기준입니다. 필요하면 `torch.set_num_threads`로 Spring과 CPU를 나눕니다.

## 7. 테스트

- **CI는 가중치를 내려받지 않습니다.** 실제 가중치로 하는 정확도 검증은 pytest가 아니라 평가 스크립트(§8)의 일입니다.
- **코드 경로는 실제 open_clip으로 테스트합니다.** `pretrained=None`으로 만든 무작위 초기화 ViT-B-32는 네트워크 없이 생성되고, 토크나이저 어휘도 패키지에 들어 있습니다. 이것을 임시 파일로 저장해 두면 다음을 실제로 검증할 수 있습니다.
  - 체크섬 검증 → 로드 → `scores()`
  - 점수 키가 `models.lock` 카테고리와 같은지
  - 값이 0~1인지
  - 투명 PNG 처리
  - 같은 입력에 같은 점수가 나오는지
- **건너뛰지 않습니다.** "가중치가 없으면 skip" 같은 마커나 `.skip`은 Gate Integrity 위반이라 사람이 이유와 함께 승인해야 합니다. 위 방식으로 skip 없이 돌게 만듭니다.
- **회귀를 잡는 테스트만**: 자기 setup만 확인하거나 구현을 복사한 테스트는 만들지 않습니다. 순수 로직(점수 집계, 라벨 결정, lock 파싱)은 Spring처럼 프레임워크 의존 없이 단위 테스트합니다.
- 기존 테스트(`ml/serving/tests`, `ml/eval/tests`)는 계속 통과해야 합니다. 특히 `version = "none"`일 때 503을 돌려주는 동작은 유지합니다.

## 8. 평가

- 평가 세트 구성은 D-37(2026-10-28 수정)을 따릅니다. 정상은 Quick, Draw! 재렌더링, 부적절은 팀이 그린 것과 코드로 만든 혐오 상징 변형입니다. Quick, Draw! 그림은 한 장을 한 사람(`drawer`)으로 봅니다.
- `manifest.csv` 형식과 실행 방법은 `ml/README.md`를 따릅니다. 세트는 S3와 로컬 `ml/eval/sets/`(git 제외)에만 둡니다.
- 모델 버전을 새로 올리는 PR에는 `evaluate.py` 출력(`report.json`의 표본 수, 오류 수, 오탐률, 재현율, p95, D-39 항목별 통과 여부)과 비교 대상 버전의 결과를 붙입니다.
- 임계값과 문장은 평가 세트로 튜닝합니다. 같은 세트로 튜닝하고 보고하면 수치가 실제보다 좋게 나오므로, 튜닝용과 보고용을 나눌지 명세에서 정합니다(추천: 나눔).
  - 참고 구현은 open_clip main의 `scripts/multilabel_zeroshot.py`입니다. 보정용 데이터로 임계값을 맞추고(`fit_thresholds`), 그와 겹치지 않는 평가 데이터로 보고합니다(`check_disjoint`).
  - 우리 임계값은 F1 최대가 아니라 **D-39 기준**으로 고릅니다. 보정 세트에서 오탐률 ≤ 2%를 지키는 가장 낮은 임계값을 고른 뒤, 보고 세트로 재현율 ≥ 70%를 확인하는 식입니다. 정하는 규칙은 명세에 적습니다.
  - 같은 사람이 그린 낙서가 두 세트에 나뉘어 들어가지 않게, 그린 사람 단위로 나눕니다. 세트 메타데이터에도 이름이나 이메일은 넣지 않고 익명 ID만 씁니다.
  - 이 스크립트는 v3.3.0 릴리스 이후 main에 추가됐고, pip 패키지에는 `scripts/`가 들어 있지 않습니다. 코드를 가져다 쓰지 말고 방식만 참고해서 `ml/eval`에 우리 코드로 구현합니다.
- 평가 세트만으로는 D-39를 다 확인할 수 없습니다. "OpenAI가 통과시켰으나 모델이 잡은 실제 낙서"와 운영 표본 수는 07b 이후 `ml_prediction`으로만 확인합니다. ENFORCE 전환은 평가 보고서를 보고 **팀이** 결정합니다.

## 9. 보안과 개인정보

- **SEC-17**
  - ML 서버는 Docker 내부망 전용입니다. compose에 `ports:`를 두지 않고, nginx도 프록시하지 않습니다.
  - 로그에는 `requestId`, 라벨, 응답 시간만 남깁니다. OCR 글자, 점수, 이미지는 남기지 않습니다. 디버깅할 때도 운영 이미지를 파일로 저장하지 않습니다.
  - 학습용 추출 데이터에는 `author_id`, 닉네임, 이메일을 넣지 않습니다(D-38).
- **SEC-06**: S3 자격 증명 같은 비밀값은 환경변수로만 받습니다. `.env`는 읽지도 쓰지도 커밋하지도 않습니다.
- **SEC-16**: 의존성 버전 고정(§6). Dependabot pip 업데이트도 평가와 테스트를 거쳐 머지합니다.
- 평가 세트와 운영 낙서 이미지는 부적절한 그림을 포함하므로 git, 이슈, PR 본문, 채팅에 올리지 않습니다. 보고서에는 수치만 적습니다.

## 10. 실패 처리

- ML 서버는 실패를 **분명한 상태 코드로** 알립니다.
  - `400`: 이미지가 아니거나 크기 초과
  - `503`: 모델 미로드나 체크섬 불일치
  - `500`: 추론 오류
- 실패를 정상 응답처럼 꾸미지 않습니다. 가짜 SAFE나 0점을 돌려주지 않습니다.
- Spring은 3초 초과, 4xx, 5xx를 모두 **ML 판정 없음**으로 처리합니다(INV-13, D-40). 이 처리는 SHADOW와 ENFORCE 모두 같고, ML 경로(이미지 분류와 OCR 글자의 텍스트 검사)의 **장애는 D-42의 게시 보류(HELD)에서 제외**합니다. 다른 검사가 정상이면 ML 장애로 게시가 막히지 않습니다.
- 장애가 아닌 **답**은 다릅니다(2026-10-28 결정). ENFORCE에서 OCR 글자 검사가 `REVIEW`를 내거나 욕설(`PROFANITY`)을 찾으면 HELD, 카테고리 2~4를 찾으면 거부입니다(`FILTER.md` §5). SHADOW에서는 모두 기록만 합니다.
- 그래서 ML 서버의 장애는 사용자 경험에 영향이 없는 대신 **조용히 묻힐 수 있습니다**. 07b에서 `ml_prediction.outcome`(TIMEOUT, ERROR) 비율을 볼 수 있게 합니다.

## 11. 하지 않는 것

- APPROVED 명세 없이 `ml/serving`에 분류기나 OCR을 넣는 것
- 코드에서 ML 모드를 바꾸거나, 기준을 넘었다고 ENFORCE로 자동 전환하는 것
- 미세조정과 `ml/training/` 작성(TODOS, 운영 데이터 이후)
- 운영 중 Hugging Face나 인터넷에서 가중치를 내려받는 것, GPU 전제 코드
- 가중치, 평가 이미지, 학습 추출본을 git에 넣는 것
- ML 서버 포트를 외부에 여는 것
- MVP에 없는 기능(신고, 자동 차단 등). 떠오르면 `TODOS.md`에 What/Why/Context로 적습니다

## 12. 열린 항목 (사람이 처리)

| 항목 | 할 일 | 언제 |
|---|---|---|
| 모델 카드 권고(§3) | 팀 합의 후 `DECISIONS.md` D-37에 "알려진 위험과 대응" 추가 | `ml-zeroshot` 명세 승인 전 |
| D-42와 ML | D-42 문구에 "ML 경로 제외(INV-13)" 반영 | 07 명세 작업 |
| ARCHITECTURE §4.7 | "OpenAI 장애 → UNCHECKED"를 D-42(HELD)에 맞게 갱신 | 07 명세 작업 |
| `models.lock` 형식 확장 | 키 이름, 문장 파일 위치, `lock.py` 검증 | `ml-zeroshot` 명세 |
| 점수 방식(paired / softmax), 임계값 고르는 규칙, 튜닝·보고 세트 분리 | 평가 세트 결과로 결정(§5, §8) | `ml-zeroshot` 명세 |
| OCR 엔진 | 한국어 손글씨 정확도 비교 후 결정(D-35) | 07b |

## 13. 참고

- OpenCLIP 문서: [README](https://github.com/mlfoundations/open_clip) (Usage, Loading models 절), [`docs/PRETRAINED.md`](https://github.com/mlfoundations/open_clip/blob/main/docs/PRETRAINED.md), [`docs/openclip_results.csv`](https://github.com/mlfoundations/open_clip/blob/main/docs/openclip_results.csv), [`docs/multilabel_zeroshot.md`](https://github.com/mlfoundations/open_clip/blob/main/docs/multilabel_zeroshot.md), `docs/Interacting_with_open_clip.ipynb`(로컬 실험용)
- OpenCLIP 코드 (`src/open_clip/`)
  - `factory.py`: `create_model_and_transforms`, `load_checkpoint`, 로컬 경로와 safetensors 로드
  - `zero_shot_classifier.py`: `build_zero_shot_classifier`
  - `zero_shot_metadata.py`: 프롬프트 템플릿
  - `model_configs/ViT-B-32.json`: 입력 224px, `context_length` 77
  - `transform.py`: `preprocess`
  - `tokenizer.py`: 어휘 파일이 패키지에 포함됨
  - `pretrained.py`: 태그와 HF 저장소·파일 대응
- OpenCLIP 평가 참고: `scripts/multilabel_zeroshot.py`(main 전용. paired 점수, 임계값 보정, 보정·평가 세트 분리), `src/open_clip_train/zero_shot.py`(제로샷 평가 루프)
- 보지 않아도 되는 것: `src/open_clip_train/`의 나머지(학습), `scripts/*.sh`(SLURM 학습), linear probe, CLAP, GenLIP, CoCa, MaMMUT, NaFlex, 오디오 관련 파일
- 모델 카드: [laion2B ViT-B-32](https://huggingface.co/laion/CLIP-ViT-B-32-laion2B-s34B-b79K), [DataComp.XL ViT-B-32](https://huggingface.co/laion/CLIP-ViT-B-32-DataComp.XL-s13B-b90K)
- 프로젝트 원문: D-34~D-42, `ARCHITECTURE.md` §4.7, `API.md` §7, `DB.md`(`ml_prediction`), `SECURITY.md` SEC-16/17, `specs/ml-prep.md`, `TODOS.md`
