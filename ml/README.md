# ml — 자체 ML 모더레이션 (D-34 ~ D-40)

| 경로 | 내용 |
|---|---|
| `serving/` | FastAPI 추론 서버. `POST /v1/moderate`, `GET /health` (`docs/API.md` §7) |
| `eval/` | 평가 세트로 D-39 지표를 계산하는 스크립트 |
| `models.lock` | 서버가 불러올 모델 버전, S3 경로, 파일 이름, sha256, 카테고리별 임계값 |

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

## 모델 붙이기 (제로샷 분류기, D-37)
1. `ImageClassifier` 프로토콜(`serving/app/models.py`)을 구현합니다. `scores(image)`는 카테고리 이름에서 0~1 점수로 가는 dict를 돌려줍니다. 예: `{"sexual": 0.81, "violence": 0.04, "hate_symbol": 0.02, "safe": 0.13}`
2. `serving/app/main.py`의 `classifier_factory()`가 그 분류기를 만드는 함수를 돌려주게 바꿉니다. 함수는 sha256 검증을 통과한 가중치 파일 경로를 받습니다
3. 필요한 라이브러리(예: CPU용 PyTorch, open_clip)를 `requirements.txt`에 버전을 고정해서 추가합니다(SEC-16)
4. 가중치를 `s3://<bucket>/models/<version>/<file>`에 올리고 `models.lock`에 `version`, `uri`, `file`, `sha256`, `[image.thresholds]`를 적습니다
5. 아래 평가를 돌리고, 그 결과를 `models.lock` 변경과 같은 PR에 붙입니다

라벨은 서버가 정합니다. `safe`가 아닌 카테고리 중 하나라도 임계값 이상이면 `FLAGGED`, 아니면 `SAFE`입니다.

## 평가 세트와 지표 (D-37, D-39)
- 팀이 그린 정상 낙서와 부적절한 낙서(각 수백 개)를 PNG로 모읍니다. 크기는 서버 상한(1024×1024) 이하로 합니다. 앱에서 그리기(05)가 생기기 전에는 같은 크기의 흰 캔버스에 마우스로 그립니다
- 세트는 S3 `s3://<bucket>/eval/<세트 이름>/`에 두고, 로컬에서는 `ml/eval/sets/`(git 제외)에 받아 씁니다
- 같은 폴더에 `manifest.csv`를 둡니다. 헤더는 `file,label`이고 `label`은 `ok` 또는 `bad`입니다
- 실행:
  ```bash
  cd ml/eval
  python evaluate.py --manifest sets/<세트>/manifest.csv --server http://localhost:8000 --out report.json
  ```
- 출력: 정상·부적절 표본 수, 오류 수, 오탐률, 재현율, 응답 시간 p95, D-39 기준별 통과 여부
- "OpenAI가 통과시켰으나 모델이 잡은 실제 낙서 1건 이상"은 운영 데이터(`ml_prediction`, 07b)로만 확인할 수 있습니다

## 지켜야 할 것 (SEC-17)
- 서버 로그에 OCR 글자, 점수, 이미지를 남기지 않습니다. 남기는 것은 `requestId`, 라벨, 응답 시간뿐입니다
- 서버 포트는 Docker 내부망에만 둡니다. compose에서 `ports:`를 열지 않고 nginx도 프록시하지 않습니다
- 학습용 추출 데이터에는 작성자 정보(`author_id`, 닉네임, 이메일)를 넣지 않습니다
