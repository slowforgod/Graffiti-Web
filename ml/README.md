# ml — 자체 ML 모더레이션 (D-34 ~ D-40)

| 경로 | 내용 |
|---|---|
| `serving/` | FastAPI 추론 서버. `POST /v1/moderate`, `GET /health` (`docs/API.md` §7) |
| `eval/` | 평가 세트 생성(`build_set.py`), 임계값 보정(`calibrate.py`), D-39 지표(`evaluate.py`), OCR 평가(`ocr_eval.py`) |
| `prompts/` | 제로샷 카테고리 문장 (`<version>.toml`) |
| `OCR_samples/` | 외부 손글씨 데이터 (로컬 전용, git 제외) |
| `models.lock` | 서버가 불러올 모델 버전, arch, S3 경로, 파일 이름, sha256, 문장 파일, 카테고리별 임계값 |

개발 규칙과 방향(명세 절차, 모델 선택, 버전 규칙, 테스트)은 `DEVELOPMENT.md`에 있습니다.

모델 가중치, 평가 이미지, 학습 데이터 추출본은 **git에 넣지 않습니다**. S3에 버전별로 두고 저장소에는 `models.lock`만 둡니다(SEC-17).

## 서버 실행
```bash
cd ml/serving
python3.12 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
pytest
MODEL_DIR=/path/to/weights uvicorn app.main:build --factory --port 8000
```

`models.lock`의 `image.version`이 `"none"`이면 서버는 뜨지만 `/health`와 `/v1/moderate`가 503입니다. Spring은 503을 "ML 판정 없음"으로 처리합니다(INV-13).

## 모델 (제로샷 분류기, D-37, `docs/specs/ml-zeroshot.md`)
분류기는 OpenCLIP ViT-B-32 제로샷입니다(`serving/app/clip_classifier.py`). 카테고리마다 positive 문장(잡을 그림)과 negative 문장(가장 비슷한 평범한 낙서)의 임베딩 평균을 시작할 때 한 번 계산하고, 요청마다 `sigmoid(logit_scale × (cos(이미지, positive) − cos(이미지, negative)))`로 0~1 점수를 냅니다. 카테고리끼리 독립이고 `safe` 점수는 없습니다.

- 응답 `scores` 예: `{"SEXUAL": 0.81, "HATE": 0.04}`. 키는 `docs/FILTER.md` 카테고리 이름이고 `[image.thresholds]` 키와 같습니다. 지금은 `SEXUAL`, `HATE`만 봅니다(폭력은 보지 않음, D-37)
- 라벨은 서버가 정합니다. 카테고리 중 하나라도 임계값 이상이면 `FLAGGED`, 아니면 `SAFE`입니다
- 카테고리 문장은 `prompts/<version>.toml`에 둡니다. 영어, 77토큰 이하. 문장을 바꾸면 점수가 모두 바뀌므로 새 버전 파일을 만들고 다시 평가합니다
- 가중치는 Hugging Face의 `open_clip_model.safetensors`만 씁니다(`.bin`은 pickle이라 금지). 후보는 `laion2b_s34b_b79k`, `datacomp_xl_s13b_b90k`입니다. 내려받기는 Mac에서 하고, 서버는 `MODEL_DIR`의 로컬 파일만 읽습니다(컨테이너는 `HF_HUB_OFFLINE=1`)
- 설정: `MODEL_DIR`, `PROMPTS_DIR`(기본 `ml/prompts`), `TORCH_THREADS`(기본 2), `MAX_IMAGE_SIDE`, `MAX_IMAGE_BYTES`

`models.lock`의 `[image]` 키 (전부 있어야 로드합니다. sha256이 하나라도 다르면 503)

| 키 | 뜻 |
|---|---|
| `version` | `clip-zs-YYYY.MM.DD`. `"none"`이면 모델 없음(503) |
| `arch` | open_clip 모델 이름(`ViT-B-32`). `hf-hub:` 같은 원격 이름은 거부 |
| `uri`, `file`, `sha256` | S3 위치, `MODEL_DIR` 안의 파일 이름, 체크섬 |
| `prompts`, `prompts_sha256` | `ml/prompts/` 안의 문장 파일 이름과 체크섬 |
| `crop_to_content` | `true`면 그린 부분만 잘라 정사각형으로 맞춘 뒤 판정 |
| `[image.thresholds]` | 카테고리별 임계값. 문장 파일의 카테고리와 정확히 같아야 함 |

새 버전 올리는 순서
1. 가중치를 `s3://<bucket>/models/<version>/open_clip_model.safetensors`에 올리고, 문장 파일을 `prompts/<version>.toml`로 만듭니다
2. `models.lock`에 위 키를 적습니다(임계값은 임시값). `shasum -a 256`으로 두 체크섬을 넣습니다
3. 서버를 띄우고 아래 평가 세트로 `calibrate.py`를 돌려 임계값을 정합니다
4. 정한 임계값을 `models.lock`에 넣고, 보고서(수치만)를 같은 PR에 붙입니다

## OCR (D-35, `docs/specs/07b-ml-moderation.md`)
손글씨는 EasyOCR 1.7.2(한국어+영어, CPU)로 읽습니다(`serving/app/ocr.py`). 글자 덩어리마다 신뢰도가 나오고, `[ocr] min_confidence` 미만인 덩어리는 버립니다. 글자 수 하한은 없습니다. 남은 글자는 Spring이 텍스트 검사 경로로 넘깁니다(07b PR 2).

- 가중치: 공식 릴리스의 `craft_mlt_25k.pth`(글자 위치 검출)와 `korean_g2.pth`(한국어 인식)를 `MODEL_DIR`에 **이 이름 그대로** 둡니다. 서버는 `models.lock`의 sha256과 EasyOCR 자체 md5를 둘 다 확인하고, 다운로드는 끕니다(`download_enabled=False`)
- 이 파일들은 pickle 형식(`.pth`)이라 바꿔치기되면 코드가 실행될 수 있습니다. 그래서 공식 릴리스에서 받은 파일의 sha256으로만 엽니다(07b 결정 2)
- `[ocr]` 키: `version`, `engine`(`easyocr`), `langs`, `min_confidence`, `[ocr.detector]`와 `[ocr.recognizer]`의 `file`·`sha256`·`uri`. 하나라도 틀리거나 파일이 없으면 서버는 503입니다

손글씨 평가와 `min_confidence` 고르기 (07b 결정 3: 평범한 글자 덩어리의 90%가 남는 값)
```bash
cd ml/eval
python ocr_eval.py --model-dir <가중치 폴더> --manifest sets/<손글씨 세트>/manifest.csv --out ocr-report.json
python ocr_eval.py --model-dir <가중치 폴더> --aihub-json ../OCR_samples/jsons/handwriting_data_info_clean.json --images ../OCR_samples/raw --limit 400
```
- `manifest.csv`는 `file,text[,category]`입니다. `category`는 일부러 넣은 욕·개인정보 샘플의 FILTER 카테고리, 평범한 글자는 비웁니다
- 출력: 표본 수, 게이트 전후 글자 오류율(CER, 띄어쓰기 무시), 남는 덩어리 비율, 신뢰도 p10·p50·p90, 카테고리별 CER, 응답 시간 p95. 읽은 글자는 출력하지 않습니다
- AI Hub "한글 손글씨" 데이터(`ml/OCR_samples/`)는 재배포가 금지돼 있어 git에 올리지 않습니다(`.gitignore`). 종이에 펜으로 쓴 문장이라 우리 캔버스의 마우스 글씨와는 다릅니다. 팀이 캔버스에 쓴 세트로 다시 확인합니다

## 평가 세트와 지표 (D-37, D-39)
세트는 `build_set.py`로 만듭니다. 세트는 S3 `s3://<bucket>/eval/<세트 이름>/`에 두고, 로컬에서는 `ml/eval/sets/`(git 제외)에 받아 씁니다.
- **정상**: [Quick, Draw!](https://github.com/googlecreativelab/quickdraw-dataset) "simplified" ndjson(Google, **CC BY 4.0**)을 우리 캔버스처럼 다시 그립니다(흰 배경, 앱의 선 굵기와 색, 1024px 이하). 게임이 알아본 그림만 쓰고, 그림 한 장 = 그린 사람 한 명입니다
- **부적절**: 팀이 그린 것(카테고리마다 30~50개, `<폴더>/<CATEGORY>/<drawer>/*.png`)과 코드로 만든 혐오 상징 변형(회전, 크기, 선 떨림). 불교 卍은 혐오 상징이 아니므로 만들지 않습니다
- `manifest.csv` 헤더는 `file,label,category,drawer`입니다. `label`은 `ok` 또는 `bad`, `category`는 부적절한 그림의 FILTER 카테고리, `drawer`는 그린 사람의 익명 ID입니다

```bash
cd ml/eval
python build_set.py --out sets/<세트> --quickdraw cat.ndjson house.ndjson --per-file 200 --hate-variants 300 --team <팀 폴더>
python calibrate.py --manifest sets/<세트>/manifest.csv --server http://localhost:8000 --out report.json
python evaluate.py --manifest sets/<세트>/manifest.csv --server http://localhost:8000   # lock의 임계값 그대로 D-39 확인
```

- `calibrate.py`는 `drawer` 단위로 보정 60% / 보고 40%로 나눕니다(같은 사람 그림이 양쪽에 섞이지 않게). 보정 세트에서 카테고리마다 **정상 낙서의 2% ÷ 카테고리 수 이하만 걸리는 가장 낮은 임계값**을 고르고, 보고 세트로 오탐률, 재현율(카테고리별 포함), p95, 표본 수, D-39 통과 여부를 출력합니다
- 서버의 임계값은 무시하고 점수만 씁니다. 출력은 수치뿐이며 이미지와 파일 이름은 보고서에 넣지 않습니다
- "OpenAI가 통과시켰으나 모델이 잡은 실제 낙서 1건 이상"은 운영 데이터(`ml_prediction`, 07b)로만 확인할 수 있습니다

## 지켜야 할 것 (SEC-17)
- 서버 로그에 OCR 글자, 점수, 이미지를 남기지 않습니다. 남기는 것은 `requestId`, 라벨, 응답 시간뿐입니다
- 서버 포트는 Docker 내부망에만 둡니다. compose에서 `ports:`를 열지 않고 nginx도 프록시하지 않습니다
- 학습용 추출 데이터에는 작성자 정보(`author_id`, 닉네임, 이메일)를 넣지 않습니다
