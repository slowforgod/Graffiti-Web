# 기여 안내

## 시작하기

로컬 실행 방법은 [README.md](README.md)의 "로컬에서 실행하기"에 있습니다. 필요한 프로그램(Java 21, Node 22, Docker)만 설치하면, 나머지 라이브러리는 각 도구가 잠금 파일에 적힌 버전 그대로 받습니다.

- 프론트엔드: `npm ci` (`frontend/package-lock.json` 기준)
- 백엔드: `./gradlew` (Gradle이 의존성을 받음)
- ML 서버: `pip install -r ml/serving/requirements.txt` (모든 버전 고정)

라이브러리를 하나씩 따로 받을 필요는 없습니다.

## 비밀값과 `.env`

- `.env`는 각자 로컬에서 만들고 **절대 커밋하지 않습니다** (`.gitignore`에 들어 있음).
- 키 목록과 뜻은 README.md의 표에 있습니다. Google OAuth 클라이언트 같은 키는 각자 자기 계정으로 만들어 씁니다. 운영 서비스의 키는 공유하지 않습니다.
- `ADMIN_EMAILS`에는 자기 이메일만 넣습니다.

## 넣으면 안 되는 파일

- 모델 가중치(`*.pt`, `*.safetensors` 등), 학습·평가 데이터, 외부 손글씨 데이터
- 글꼴 원본 파일 (`frontend/designs/fonts/`)
- 출처와 라이선스를 모르는 그림, 글꼴, 데이터

새 글꼴은 `frontend/src/fonts/LICENSES.md`에 항목을 추가해야 테스트를 통과합니다. 새 그림이나 데이터는 PR 설명에 출처와 라이선스를 적어 주세요.

## 새 의존성

새 라이브러리를 추가할 때는 PR 설명에 이유를 적고, 잠금 파일(`package-lock.json`, `requirements.txt`의 고정 버전)도 함께 커밋합니다. 의존성 업데이트는 Dependabot이 매주 PR로 올립니다.

## PR

- 설계와 규칙은 [CLAUDE.md](CLAUDE.md)와 [`docs/`](docs/)를 따릅니다.
- PR을 올리면 CI(테스트, gitleaks 비밀값 검사)가 돕니다. 외부 기여자의 PR에서는 저장소 비밀값을 쓰지 않습니다.
- 머지는 "Rebase and merge"만 씁니다. 브랜치에 `main`을 merge하지 말고 rebase해 주세요.

## 보안 문제

보안 문제는 이슈로 올리지 말고 [SECURITY.md](SECURITY.md)의 방법으로 알려 주세요.
