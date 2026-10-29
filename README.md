# Graffiti-Web

경희대 온라인 낙서장. 모든 사용자가 하나의 큰 캔버스에 펜과 텍스트로 낙서하는 실시간 웹 서비스입니다. 규칙과 설계 문서는 [`CLAUDE.md`](CLAUDE.md)와 [`docs/`](docs/)에 있습니다.

## 로컬에서 실행하기

아래 명령은 macOS 기준입니다.

### 1. 필요한 프로그램 설치 (처음 한 번)

필요한 것은 Java 21, Node 22, Docker Desktop입니다. macOS에서는 [Homebrew](https://brew.sh)로 터미널에서 모두 설치할 수 있습니다.

Homebrew가 없으면 먼저 설치합니다.

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Java 21, Node 22, Docker Desktop을 설치합니다.

```bash
brew install --cask temurin@21
brew install node@22
brew install --cask docker-desktop
```

`node@22`는 자동으로 PATH에 잡히지 않으므로 한 번 등록하고 터미널을 새로 엽니다.

```bash
echo "export PATH=\"$(brew --prefix node@22)/bin:\$PATH\"" >> ~/.zshrc
```

Docker Desktop은 설치 후 한 번 실행해 둡니다.

```bash
open -a Docker
```

설치가 됐는지 확인합니다.

```bash
java -version    # 21.x
node -v          # v22.x
docker info      # 오류 없이 나오면 Docker가 실행 중
```

명령으로 설치할 수 없거나 Windows라면 공식 사이트에서 받습니다.

| 프로그램 | 다운로드 |
|---|---|
| Java 21 (Eclipse Temurin) | <https://adoptium.net/temurin/releases/?version=21> |
| Node 22 | <https://nodejs.org/en/download> (버전에서 v22 선택) |
| Docker Desktop | <https://www.docker.com/products/docker-desktop/> |

### 2. 저장소 받기와 `.env` 만들기 (처음 한 번)

```bash
git clone https://github.com/slowforgod/Graffiti-Web.git
cd Graffiti-Web
```

저장소 루트에 `.env` 파일을 만들고 아래 키를 `키=값` 형식으로 한 줄에 하나씩 적습니다. `.env`는 git에 올리지 않습니다(SEC-06). 예시 파일은 두지 않으므로 키 목록은 이 표가 기준입니다.

| 키 | 값 |
|---|---|
| `DB_USERNAME` | `graffiti` |
| `DB_PASSWORD`, `DB_ROOT_PASSWORD` | 각자 정한 값 (로컬 DB에만 쓰임) |
| `APP_ORIGIN` | `http://localhost:5173` |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | 팀에서 비공개로 전달받은 값 |

아래 키는 로컬 실행에 없어도 됩니다.

| 키 | 값 |
|---|---|
| `DB_URL` | 백엔드의 DB 주소. 비우면 `application.yml`의 기본값 |
| `ADMIN_EMAILS` | 관리자 이메일, 쉼표로 구분 (D-23). 여기 적은 이메일은 학교 계정이 아니어도 로그인됨 (D-23a) |
| `POST_COOLDOWN` | 게시 후 다음 게시까지 기다리는 시간. 기본 `0s`(바로 다시 게시), 사용자가 늘면 `30m` (D-12) |
| `OPENAI_API_KEY`, `CHATKHU_API_KEY` | 모더레이션 제공자 키 (07) |
| `MODERATION_UNCHECKED` | `PUBLISH`로 두면 검사하지 않은 낙서도 게시됨. 로컬 그리기 테스트에만, 공개 서버에는 절대 쓰지 않음 |
| `IMAGE_PREFIX`, `DOMAIN`, `LETSENCRYPT_EMAIL` | 스테이징·운영 서버 전용 (`deploy/README.md` §3) |

### 3. 실행

터미널 두 개를 엽니다. 둘 다 저장소 폴더(`Graffiti-Web`)에서 시작합니다.

**터미널 1: DB와 백엔드**

```bash
docker compose up -d mysql
set -a; source .env; set +a
cd backend && ./gradlew bootRun
```

`Started GraffitiApplication`이 보이면 준비된 것입니다. 진행 표시가 `80% EXECUTING`에 머무는 것은 서버가 실행 중이라는 뜻이니 그대로 둡니다.

**터미널 2: 프론트**

```bash
cd frontend
npm ci        # 처음 한 번, 또는 package-lock.json이 바뀐 뒤
npm run dev
```

Chrome에서 <http://localhost:5173>을 열고 @khu.ac.kr Google 계정으로 로그인합니다.

끌 때는 각 터미널에서 `Ctrl+C`를 누르고, DB는 `docker compose stop mysql`로 멈춥니다.

### 자주 걸리는 문제

- **Safari에서 로그인이 안 됨**: Safari는 localhost에서 보안 쿠키를 저장하지 않습니다. Chrome을 씁니다.
- **3306 포트가 이미 사용 중**: 컴퓨터에 MySQL이 따로 켜져 있으면 DB 컨테이너가 뜨지 않습니다. 그 MySQL을 끄거나(`brew services stop mysql`), `docker-compose.yml`의 포트와 `.env`의 `DB_URL`을 다른 포트로 맞춥니다.
- **Google 로그인이 거부됨**: Google OAuth 앱이 테스트 상태이면 Google Cloud 콘솔의 테스트 사용자에 계정을 추가해야 합니다.
- **`docker compose`에서 연결 오류**: Docker Desktop이 실행 중인지 확인합니다(`open -a Docker`).

## 테스트

```bash
cd backend && ./gradlew test                    # 백엔드 (Docker 필요)
cd frontend && npm test && npm run lint         # 프론트
```

전체 명령 목록은 [`CLAUDE.md`](CLAUDE.md) 2절에 있습니다.
