# 스테이징 배포 (12a)

main에 머지되고 CI가 통과하면 `.github/workflows/deploy.yml`이 이미지 두 개를 GHCR에 올립니다.

- `ghcr.io/<owner>/graffiti-app:<커밋 SHA>`: Spring 앱
- `ghcr.io/<owner>/graffiti-web:<커밋 SHA>`: nginx와 프론트

저장소 변수 `STAGING_HOST`가 있으면 서버에 SSH로 접속해 그 태그로 `docker compose up -d`까지 합니다. 아래는 **사람이 한 번 해야 하는 준비**입니다. 비밀값은 서버의 `/opt/graffiti/.env`와 GitHub에만 두고, 이 저장소에는 넣지 않습니다(SEC-06).

## 1. 서버 (EC2)
1. Ubuntu 24.04, 메모리 4GB급 인스턴스(D-36), 디스크 20GB 이상
2. 보안 그룹 인바운드(SEC-10)
   - 80, 443: 전체
   - 22: 팀원 IP와 GitHub Actions 배포용. Actions의 IP는 고정이 아니므로 처음에는 22를 열고 12b에서 좁힙니다
   - 그 밖의 포트(3306, 8080 포함): 열지 않음
3. Docker Engine과 compose 플러그인을 설치합니다: <https://docs.docker.com/engine/install/ubuntu/>
4. 배포용 사용자를 만들고 docker 그룹에 넣습니다. 예: `deploy`
5. `/opt/graffiti` 디렉터리를 만들고 소유자를 그 사용자로 바꿉니다
6. 고정 IP(Elastic IP)를 붙입니다

## 2. DNS
스테이징 도메인(예: `staging.<도메인>`)의 A 레코드를 Elastic IP로 지정합니다.

## 3. 서버의 `.env`
`/opt/graffiti/.env`를 만들고 권한은 `chmod 600`으로 둡니다. 키 목록은 저장소의 `.env.example`과 같습니다. 스테이징에 필요한 값은 아래와 같습니다.

| 키 | 값 |
|---|---|
| `IMAGE_PREFIX` | `ghcr.io/<owner 소문자>/graffiti` |
| `DOMAIN` | 스테이징 도메인 |
| `LETSENCRYPT_EMAIL` | 인증서 만료 알림을 받을 주소 |
| `DB_USERNAME`, `DB_PASSWORD`, `DB_ROOT_PASSWORD` | 새로 만든 긴 무작위 값 |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | 아래 5번에서 만든 값 |
| `ADMIN_EMAILS` | 관리자 학교 이메일, 쉼표로 구분 |

## 4. GitHub 설정 (Settings → Secrets and variables → Actions)
| 종류 | 이름 | 값 |
|---|---|---|
| Variable | `STAGING_HOST` | 스테이징 도메인. **이 값을 넣는 순간부터 main 머지마다 배포됩니다** |
| Variable | `STAGING_SSH_USER` | 1-4의 배포용 사용자 |
| Secret | `STAGING_SSH_KEY` | 배포 전용으로 새로 만든 SSH 개인 키. 공개 키는 서버 사용자의 `~/.ssh/authorized_keys`에 넣습니다 |
| Secret | `STAGING_SSH_KNOWN_HOSTS` | `ssh-keyscan -t ed25519 <도메인>`의 출력 |

이미지는 비공개 GHCR 패키지입니다. 배포 작업이 실행될 때마다 그 작업의 토큰으로 서버에서 로그인하고, 끝나면 로그아웃합니다.

## 5. Google OAuth 클라이언트
Google Cloud 콘솔 → API 및 서비스 → 사용자 인증 정보 → OAuth 클라이언트 ID(웹 애플리케이션)에서 만듭니다.

승인된 리디렉션 URI에는 두 주소를 넣습니다.
- `https://<스테이징 도메인>/login/oauth2/code/google`
- 로컬 개발용 `http://localhost:5173/login/oauth2/code/google`

## 6. 첫 배포와 인증서
1. `STAGING_HOST`를 넣은 뒤 main에 머지하거나, 마지막 CI 실행의 Deploy 워크플로를 다시 실행합니다
   - 인증서가 아직 없으므로 nginx는 HTTP 전용 설정으로 뜹니다. 로그인은 `Secure` 쿠키 때문에 HTTPS가 생긴 뒤에 됩니다
   - 이때 배포 작업의 마지막 단계(HTTPS 헬스체크)는 실패합니다
2. 서버에서 인증서를 발급합니다.
   ```bash
   cd /opt/graffiti
   set -a; . ./.env; set +a
   docker compose --env-file .env --env-file image.env run --rm --entrypoint certbot certbot \
     certonly --webroot -w /var/www/certbot -d "$DOMAIN" \
     --email "$LETSENCRYPT_EMAIL" --agree-tos --no-eff-email
   docker compose --env-file .env --env-file image.env restart web
   ```
3. 이제 `https://<도메인>/actuator/health`가 `{"status":"UP"}`이고, http는 https로 리다이렉트됩니다
   - 갱신은 certbot 컨테이너가 12시간마다 시도하고, nginx는 6시간마다 다시 읽습니다

## 7. 되돌리기
이전 커밋 SHA로 다시 띄웁니다.

```bash
cd /opt/graffiti
printf 'IMAGE_TAG=%s\n' <이전 SHA> > image.env
docker compose --env-file .env --env-file image.env up -d
```

DB 마이그레이션이 들어간 커밋은 되돌려도 스키마가 남습니다. 12a 스테이징 데이터는 버려도 됩니다.

## 로컬에서 같은 구성 확인하기
```bash
(cd backend && ./gradlew bootJar) && (cd frontend && npm ci && npm run build)
docker build -f deploy/app.Dockerfile -t local/graffiti-app:dev .
docker build -f deploy/web.Dockerfile -t local/graffiti-web:dev .
```

그다음 `IMAGE_PREFIX=local/graffiti`, `IMAGE_TAG=dev`, `DOMAIN=localhost`와 DB, Google 값을 담은 env 파일을 따로 만들어 `docker compose -f deploy/compose.yml --env-file <그 파일> up -d`로 띄웁니다. 비밀값이 든 `.env`는 커밋하지 않습니다.
