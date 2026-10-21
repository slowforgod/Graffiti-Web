# 12a-deploy-staging: 스테이징 배포 골격 (이미지, compose, nginx, TLS, Actions)

- **상태**: MERGED (PR #18, 2026-10-21)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-21 (결정 필요 1~3은 추천안으로 확정)
- **브랜치**: `feat/12a-deploy-staging`
- **선행 명세**: 00

## 1. 왜 (Why)
기능을 하나씩 붙일 때마다 실제 도메인, HTTPS, nginx 뒤에서 확인할 곳이 없으면 쿠키(`Secure`), OAuth 리디렉션 URI, WebSocket 업그레이드 같은 문제가 출시 직전(12b)에야 드러난다. 이 명세는 main 머지마다 같은 이미지를 스테이징 EC2에 올리는 골격을 만든다. 운영 공개, 백업, 복구 리허설은 12b에서 한다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-02 | EC2 1대, Docker, nginx + Let's Encrypt, GitHub Actions, 같은 도메인 서빙 |
| 결정 | D-04 | 월 고정비 최소: 앱, MySQL, nginx가 한 서버의 compose |
| 결정 | D-36 | ML 컨테이너는 07b에서 같은 compose에 추가 (이 명세는 자리만 문서화) |
| 보안 | SEC-03 | HTTPS 강제, `Secure` 쿠키가 동작하려면 TLS 필요 |
| 보안 | SEC-06 | 비밀값은 서버의 `.env`와 GitHub Secrets에만. 이미지에 넣지 않음 |
| 보안 | SEC-08/09 | nginx `limit_req`(IP당 REST 속도), `limit_conn`(IP당 WebSocket 연결) |
| 보안 | SEC-10 | MySQL 포트 공개 없음, 외부에서 `/actuator`는 `health`만, 80/443만 |
| 보안 | SEC-16 | 베이스 이미지와 Actions 버전 고정, Dependabot에 docker 추가 |
| API | `API.md` §0 | nginx가 내는 429, 413도 같은 에러 형식(`RATE_LIMITED`, `PAYLOAD_TOO_LARGE`) |
| 아키텍처 | `ARCHITECTURE.md` §7 | 배포 구성 확정 |

## 3. 범위
**포함**
- `deploy/app.Dockerfile`: CI가 만든 `graffiti.jar`를 담는 JRE 이미지(비 root 사용자)
- `deploy/web.Dockerfile`: nginx + 프론트 `dist` + 설정. 시작 시 인증서가 있으면 HTTPS 설정, 없으면 HTTP 전용 설정(ACME 챌린지 응답용)을 고른다
- `deploy/nginx/`: 정적 파일(SPA 폴백), `/api`, `/oauth2`, `/login`, `/ws` 프록시, 속도 제한, 보안 헤더, `/actuator`는 `health`만, nginx 에러(429, 413, 502)도 API 에러 형식
- `deploy/compose.yml`: `web`(80/443), `app`, `mysql`(포트 없음, 볼륨), `certbot`(갱신 루프). 이미지 태그는 `IMAGE_TAG`
- `.github/workflows/deploy.yml`: main push → 테스트 통과한 커밋으로 jar와 dist 빌드 → GHCR에 `app`, `web` 이미지 push(태그: 커밋 SHA) → 저장소 변수 `STAGING_HOST`가 있을 때만 SSH로 서버에서 `docker compose pull && up -d`
- 백엔드: `server.forward-headers-strategy: native`(nginx 뒤에서 `https` 리디렉션 URI), `bootJar` 이름 고정
- `deploy/README.md`: 사람이 할 일(EC2, 보안 그룹, DNS, 서버 `.env`, 첫 인증서 발급, GitHub 변수와 Secrets, Google 리디렉션 URI)
- `.dockerignore`, Dependabot docker 항목, `.env.example`에 서버용 키 추가, `ARCHITECTURE.md` §7, `CLAUDE.md` §2 배포 설정

**제외 (NOT in scope)**
- 운영 공개, 운영 도메인 → 12b
- 매일 백업과 복구 리허설(SEC-11) → 12b (스테이징 데이터는 버려도 됨)
- ML 컨테이너 → 07b에서 compose에 추가
- 실제 EC2 생성, DNS, 인증서 발급, Secrets 등록 → AWS 계정과 도메인이 필요해서 사람이 `deploy/README.md` 순서대로 진행
- 무중단 배포 → 서버 1대(D-01). `up -d`로 재시작하는 동안 수 초 끊김을 허용

## 4. 현재 상태 (검증된 사실)
- `docker-compose.yml`은 로컬 MySQL 전용(127.0.0.1 바인딩). 주석에 스테이징 compose는 12a에서 만든다고 적혀 있음
- `.github/workflows/ci.yml`: PR과 main push에 backend test, frontend lint/test/build, gitleaks. 배포 없음
- 백엔드는 `/actuator/health`만 노출(00). 프록시 헤더 설정 없음

## 5. 설계

```
GitHub main push
  └─ deploy.yml
       ├─ build: ./gradlew bootJar, npm ci && npm run build
       ├─ docker build deploy/app.Dockerfile → ghcr.io/<owner>/graffiti-app:<sha>
       │  docker build deploy/web.Dockerfile → ghcr.io/<owner>/graffiti-web:<sha>
       └─ (vars.STAGING_HOST 있을 때) ssh: GITHUB_TOKEN으로 ghcr 로그인 → compose.yml 갱신
                                         → IMAGE_TAG=<sha> docker compose pull && up -d

EC2 (/opt/graffiti: compose.yml, .env)
  web (nginx :80 :443) ─┬─ /            → dist (SPA, index.html 폴백)
                        ├─ /api /oauth2 /login → app:8080   (limit_req, X-Forwarded-*)
                        ├─ /ws          → app:8080 (Upgrade, limit_conn)
                        ├─ /actuator/health → app:8080, 그 밖의 /actuator → 404
                        └─ /.well-known/acme-challenge → certbot webroot
  app (Spring, 8080, 외부 포트 없음) ── mysql (3306, 외부 포트 없음, 볼륨)
  certbot (12시간마다 renew) / web은 6시간마다 reload
```

TLS 첫 발급: 인증서가 없으면 web은 HTTP 전용 설정으로 뜬다 → `docker compose run --rm certbot certonly --webroot ...` → `docker compose restart web`이면 HTTPS 설정으로 바뀌고 80은 443으로 리다이렉트.

| 파일 | 역할 |
|---|---|
| `deploy/app.Dockerfile` | `eclipse-temurin:21.0.11_10-jre-noble`, 비 root, `-XX:MaxRAMPercentage` |
| `deploy/web.Dockerfile` | `nginx:1.30.1-alpine`, dist와 설정 복사 |
| `deploy/nginx/nginx.conf` | 공통: 로그 형식(쿼리 문자열 제외, SEC-13), `limit_req_zone`, `limit_conn_zone`, gzip |
| `deploy/nginx/locations.conf` | 공통 location 블록과 에러 페이지 |
| `deploy/nginx/http-only.conf.template`, `https.conf.template` | 인증서 유무에 따른 server 블록 (`${DOMAIN}`) |
| `deploy/nginx/40-select-tls.sh` | `/docker-entrypoint.d`에서 설정 선택 |
| `deploy/compose.yml` | 서비스 4개 |
| `deploy/README.md` | 서버 준비와 첫 배포 절차 |
| `.github/workflows/deploy.yml` | 빌드, push, 배포 |

- 앱 설정값 추가 없음(INV-10). nginx 속도 제한 수치는 인프라 설정이라 `deploy/nginx/nginx.conf`에 두고 `ARCHITECTURE.md` §7에 표로 적는다
- 결정 필요 1 — 이미지 빌드 방식: **추천: CI가 jar와 dist를 만들고 Dockerfile은 복사만** (Gradle, npm 캐시를 CI가 이미 가짐, 이미지에 빌드 도구 없음). 대안: 멀티 스테이지
- 결정 필요 2 — TLS: **추천: certbot webroot + nginx 시작 스크립트가 HTTP/HTTPS 설정 선택** (D-02의 nginx + Let's Encrypt 유지). 대안: Caddy 자동 HTTPS(D-02 변경 필요)
- 결정 필요 3 — 배포 조건: **추천: 저장소 변수 `STAGING_HOST`가 있을 때만 배포 단계 실행** (서버가 준비되기 전에도 main push가 실패하지 않음)

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 배포 | 서버 준비 전 main push | 이미지 push까지만 하고 배포 단계 건너뜀 | Actions 실행 결과 |
| 배포 | 테스트 실패 커밋 | `deploy.yml`이 테스트를 먼저 돌리고 실패하면 이미지도 안 만듦 | 워크플로 `needs` |
| TLS | 인증서 없이 시작 | HTTP 전용 설정으로 기동, ACME 챌린지 응답 | 로컬 compose QA |
| TLS | 인증서 만료 | certbot 12시간 갱신, nginx 6시간 reload | 설정 점검 |
| 프록시 | app 재시작 중 | nginx 502를 API 에러 형식 `INTERNAL`로 | 로컬 QA (app 중지) |
| 남용 | IP당 요청 폭주 | 429 `RATE_LIMITED` JSON | 로컬 QA (연속 요청) |
| 노출 | `/actuator/env`, 3306 | 404 / 포트 없음 | 로컬 QA |
| 로그 | 쿼리 문자열의 OAuth `code`, `state` | nginx 접근 로그에 경로만 기록 | 설정 점검 + QA |
| 비밀값 | 이미지에 `.env` 포함 | `.dockerignore` 허용 목록 방식 | 이미지 파일 목록 확인 |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1: `deploy/compose.yml`을 로컬에서 HTTP 모드로 띄우면 `/`가 SPA, `/api/me`가 `{"authenticated":false}`, `/actuator/health`가 UP
- [x] AC-2 (SEC-10): `/actuator/env`, `/actuator` 404, mysql과 app은 호스트 포트가 없음
- [x] AC-3 (SEC-09): 같은 IP에서 버스트를 넘는 `/api` 요청은 429 `RATE_LIMITED` JSON
- [x] AC-4: app이 내려가 있으면 `/api/me`가 502 `INTERNAL` JSON (nginx 기본 HTML 아님)
- [x] AC-5: nginx를 거친 `/oauth2/authorization/google`의 `redirect_uri`가 `X-Forwarded-Proto`를 따름 (`https`로 요청하면 `https://<도메인>/login/oauth2/code/google`)
- [x] AC-6: 존재하지 않는 경로(`/some/page`)는 `index.html`로 폴백, `/api/없는경로`는 앱의 JSON 응답(비로그인 401, 로그인 404)
- [x] AC-7 (SEC-13): nginx 접근 로그에 쿼리 문자열이 없다
- [x] AC-8 (SEC-06): 이미지 안에 `.env`, 소스, `node_modules`가 없다
- [x] AC-9: 인증서 파일이 있으면 HTTPS 설정으로 기동하고 80은 443으로 리다이렉트, HSTS 헤더 (자체 서명 인증서로 확인)
- [x] AC-10: `deploy.yml`은 테스트 통과 후에만 이미지를 만들고, `STAGING_HOST`가 없으면 배포 단계를 건너뛴다

## 8. 테스트 계획
| 층 | 대상 | 파일 |
|---|---|---|
| 통합 | 프록시 헤더로 리디렉션 URI가 https | `ForwardedHeadersIT` |
| QA | 로컬 `docker compose -f deploy/compose.yml` (HTTP 모드, 자체 서명 HTTPS 모드) + curl | 수동 체크리스트 |
| QA | 실제 EC2, 도메인, Let's Encrypt | **AWS 계정, 도메인 필요** → 미검증으로 남김 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 비밀값을 이미지, compose, 워크플로에 쓰지 않는다. 서버 `.env`와 GitHub Secrets 이름만 문서화
- 베이스 이미지와 Actions는 버전(Actions는 SHA)으로 고정
- MySQL, app에 `ports:`를 두지 않는다

## 10. 롤백
이전 커밋 SHA로 `IMAGE_TAG`를 바꿔 `docker compose up -d`. 이 명세는 DB 마이그레이션이 없다.

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-21 · QA 후 커밋 · CRITICAL 0 / INFO 4 · AUTO-FIX 3 · ASK 없음 · PASS
  - AUTO-FIX 1: compose의 `command` 덮어쓰기가 nginx 이미지의 진입 스크립트를 건너뛰어 설정이 비어 있었음 → `entrypoint`에서 reload 루프를 띄운 뒤 `/docker-entrypoint.sh`를 그대로 실행
  - AUTO-FIX 2: nginx는 시작할 때 `app` 주소를 한 번만 찾아서, 배포로 app 컨테이너가 새로 만들어지면 502가 계속됨 → Docker DNS(`resolver 127.0.0.11`)와 변수 `proxy_pass`로 매번 다시 찾음 (app 재생성 후 reload 없이 200 확인)
  - AUTO-FIX 3: `error_page`가 원래 코드(504)를 유지해 AC-4와 달랐음 → `=502`로 고정, `proxy_connect_timeout 5s`로 빠르게 실패
  - INFO-1: 01 머지 전 main(V0, V3)으로 만든 DB는 V1, V2가 나중에 들어와 Flyway 검증에 실패함. 로컬 QA에서 발견. 스테이징은 아직 배포 전이라 영향 없음. 그 사이 main으로 로컬 DB를 만든 사람은 로컬 볼륨을 새로 만들어야 함
  - INFO-2: nginx 오류 로그는 요청 줄을 쿼리 문자열과 함께 남길 수 있음(예: 속도 제한 거부). 접근 로그는 `$uri`만 기록. 11-security-pass에서 오류 로그 수준을 다시 볼 것
  - INFO-3: 배포 단계는 `vars.STAGING_HOST`가 없으면 건너뜀. 이미지는 main 머지마다 GHCR에 쌓이므로 12b에서 보존 정책을 정할 것
  - INFO-4: 베이스 이미지 버전은 2026-10-21 기준 레지스트리에서 확인한 태그(temurin 21.0.11_10, nginx 1.30.1, mysql 8.4.9, certbot 5.1.0). Dependabot docker, docker-compose 항목 추가

## QA 기록 (/gf-qa가 추가)
- 2026-10-21 · 로컬 `docker compose -f deploy/compose.yml` (이미지 직접 빌드) · 통과 · 버그 3(위 AUTO-FIX 1~3) · PASS (실제 EC2는 미검증)
  - 자동: backend `./gradlew test` 112개 통과(실패 0, 스킵 0, `ForwardedHeadersIT` 3개 포함, 설정을 끄면 실패하는 것 확인), frontend 14개 통과, lint, build 통과. `actionlint` 통과, `docker compose config` 통과
  - HTTP 모드: `/` 200 SPA, `/api/me` `{"authenticated":false}`, `/actuator/health` UP, `/actuator`와 `/actuator/env` 404, `/some/page` → index.html, `/api/nope` 401 JSON, 보안 헤더(CSP, X-Frame-Options, nosniff, Referrer-Policy), assets 1년 캐시와 index.html no-cache
  - 속도 제한: `/api/me` 동시 80개 → 45개 200, 35개 429 `RATE_LIMITED` JSON
  - app 중지: 5초 안에 502 `INTERNAL` JSON / app 재생성: nginx reload 없이 복구
  - 로그: `?code=SECRETCODE123&state=...` 요청 후 nginx 접근, 오류 로그 모두에 code 없음
  - 이미지: app은 uid 999 비 root, 두 이미지 모두 `.env` 파일 없음, web에는 dist만
  - HTTPS 모드(자체 서명 인증서를 certbot 볼륨에 넣고 web 재시작): `select-tls`가 HTTPS 선택, http → 301 https(쿼리 유지), HTTP/2, HSTS, `redirect_uri=https://localhost/login/oauth2/code/google`
  - 미검증: 실제 EC2, DNS, Let's Encrypt 발급, GitHub Actions 배포 단계(서버와 `STAGING_HOST`, Secrets 필요). 이미지 push 단계는 이 PR 머지 후 첫 main CI 뒤에 확인

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
