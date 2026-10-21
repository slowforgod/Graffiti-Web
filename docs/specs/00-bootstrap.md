# 00-bootstrap: 저장소 골격, 로컬 실행 환경, CI

- **상태**: MERGED
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-21
- **브랜치**: `feat/00-bootstrap` / PR: https://github.com/slowforgod/Graffiti-Web/pull/3
- **선행 명세**: 없음

## 1. 왜 (Why)
backend, frontend 코드가 아직 하나도 없어서 01-auth, 02-page-core, 12a-deploy-staging을 시작할 수 없다. 이 명세는 세 갈래가 같은 골격, 같은 설정 방식(INV-10), 같은 에러 형식, 같은 CI 위에서 병렬로 진행되도록 바닥을 깐다. 기능 코드는 넣지 않는다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-02 | Spring Boot 3 + Java 21, React + TS + Vite, MySQL 8, Docker. 같은 도메인 서빙 → Vite dev 프록시 `/api`, `/ws` |
| 결정 | D-01 | 서버 1대. 골격도 단일 앱 모듈 하나 |
| 결정 | D-32 (PROPOSED) | JaCoCo 설정만 추가, 80% 강제(verification rule)는 D-32 확정 전까지 넣지 않음 |
| 결정 | D-40 (PROPOSED) | `ml/` 디렉터리는 이 명세에서 만들지 않음 (ML 트랙 소관) |
| 결정 | D-41 | Rebase and merge, 커밋을 bisect 가능하게 나눔 |
| 불변 조건 | INV-10 | `GraffitiProperties` 하나에 `ARCHITECTURE.md` §6 키 전체를 바인딩하고 기동 시 검증 |
| 보안 | SEC-01 | Flyway 마이그레이션만 DDL 실행. `spring.jpa.hibernate.ddl-auto=validate` |
| 보안 | SEC-06 | `.env.example`만 커밋, CI에 gitleaks |
| 보안 | SEC-10 | actuator는 `health`만 노출, `show-details=never`. 로컬 compose의 MySQL 포트는 `127.0.0.1`에만 바인딩 |
| 보안 | SEC-12 | ESLint에 `react/no-danger` = error |
| 보안 | SEC-13 | `ApiError` 500 응답에 예외 메시지, 스택 노출 금지 |
| 보안 | SEC-16 | Gradle, npm 의존성 버전 고정(`package-lock.json`, Gradle 버전 명시), Dependabot 설정 |
| API | `API.md` §0 에러 형식, §5 `/actuator/health` | 에러 응답 본문 형식과 코드 enum을 구현. 새 엔드포인트 없음 |
| DB | `DB.md` §2 | 이 명세에서는 테이블을 만들지 않음. 각 기능 명세가 자기 테이블의 마이그레이션을 추가 (01: `users`, 세션 테이블 / 02: `page`) |

## 3. 범위
**포함**
- `backend/`: Gradle(Kotlin DSL) + Wrapper, Spring Boot 3.x, Java 21 toolchain, 패키지 루트 `kr.ac.khu.graffiti`와 `ARCHITECTURE.md` §2의 `config`, `common` 패키지
  - 의존성: web, validation, actuator, data-jpa, flyway(+mysql), mysql-connector-j, test, Testcontainers(mysql, junit-jupiter), JaCoCo
  - `GraffitiProperties` (`@ConfigurationProperties("graffiti")`, `@Validated`, record 중첩) + `application.yml`에 §6 기본값 전부
  - `common/`: `ErrorCode` enum(`API.md` §0 표 14개, HTTP 상태 포함), `ApiError` 응답 record, `ApiException`, `GlobalExceptionHandler`(검증 실패 → `VALIDATION_FAILED`, 본문 초과 → `PAYLOAD_TOO_LARGE`, 그 외 → `INTERNAL`), `Clock` 빈(UTC)
  - Flyway: `db/migration` 디렉터리와 비어 있지 않은 최초 마이그레이션 없이 시작 가능하도록 `V0__init.sql`(주석만) — 첫 테이블은 01/02가 추가
  - JPA: `ddl-auto=validate`, `open-in-view=false`, 시간대 UTC (`hibernate.jdbc.time_zone=UTC`)
  - actuator: `exposure.include=health`, `show-details=never`
- `frontend/`: Vite + React + TypeScript(strict), Vitest + Testing Library, ESLint(flat config, `react/no-danger` error), `npm run dev|build|test|lint`, dev 서버 `/api`·`/ws`·`/oauth2`·`/login` → `localhost:8080` 프록시, 빈 `App`(제목만, 한국어) + `src/` 하위 디렉터리는 만들지 않음(기능 명세가 추가)
- 루트: `docker-compose.yml`(로컬용, `mysql:8.4` 고정 태그, 볼륨, `127.0.0.1:3306`만 바인딩, `DB_PASSWORD` 등은 `.env`에서), `.env.example`
- CI `.github/workflows/ci.yml`: PR과 main push 시 backend `./gradlew test`, frontend `npm ci && npm run lint && npm test && npm run build`, gitleaks 시크릿 스캔
- `.github/dependabot.yml`: gradle, npm, github-actions 주간
- `CLAUDE.md` §2 명령어를 확정 값으로 갱신

**제외 (NOT in scope)**
- 로그인, Security 설정, CSRF, 세션 → 01-auth. (00에서는 Spring Security를 넣지 않는다. 넣으면 기본 로그인 페이지가 생겨 01 설계와 섞임)
- 도메인 테이블, `PageCommandProcessor` → 01, 02
- STOMP, WebSocket → 04
- 운영용 Dockerfile, nginx, GHCR 푸시, EC2 배포 → 12a
- `ml/` → ML 트랙(ml-prep), D-40 확정 후
- 커버리지 80% 강제 → D-32 확정 후 (지금은 리포트만)

## 4. 현재 상태 (검증된 사실)
- 코드 없음. 저장소에는 `CLAUDE.md`, `TODOS.md`, `README.md`, `docs/`, `.claude/`, `.handoff/`, `.gitignore`만 있음 (main `0788ce9`)
- `.gitignore`는 이미 `.env`, `backend/build/`, `frontend/node_modules/`, `frontend/dist/`를 제외함
- `.claude/settings.json`이 `.env` 읽기/쓰기를 차단함 (SEC-06)

## 5. 설계

```
기동 순서 (backend)
  application.yml ──bind──▶ GraffitiProperties ──@Validated──▶ 실패 시 기동 중단 (잘못된 설정은 조용히 넘어가지 않음)
  DataSource ──▶ Flyway migrate ──▶ JPA validate ──▶ /actuator/health UP

요청 실패 경로
  Controller ──throw ApiException(ErrorCode, details)──▶ GlobalExceptionHandler ──▶ { "error": { code, message, details } }
  MethodArgumentNotValidException / HttpMessageNotReadable ──▶ VALIDATION_FAILED (400)
  MaxUploadSize / 본문 초과 ──▶ PAYLOAD_TOO_LARGE (413)
  그 외 Exception ──▶ INTERNAL (500), 메시지는 고정 한국어 문구, 로그에는 스택(이메일·토큰 없음)
```
큐(INV-01)와 관련된 상태 변경 경로는 이 명세에 없다.

| 파일 (예정) | 역할 |
|---|---|
| `backend/build.gradle.kts`, `settings.gradle.kts`, `gradlew*`, `gradle/wrapper/*` | 빌드, 의존성 버전 고정 |
| `backend/src/main/java/kr/ac/khu/graffiti/GraffitiApplication.java` | 진입점, `@ConfigurationPropertiesScan` |
| `.../config/GraffitiProperties.java` | §6 설정 전체 바인딩 + 검증 |
| `.../common/ClockConfig.java` | `Clock.systemUTC()` 빈 |
| `.../common/error/ErrorCode.java`, `ApiError.java`, `ApiException.java`, `GlobalExceptionHandler.java` | 에러 형식 |
| `backend/src/main/resources/application.yml` (+ `application-test.yml`) | 설정 기본값, 환경변수 참조 |
| `backend/src/main/resources/db/migration/V0__init.sql` | Flyway 시작점 |
| `frontend/package.json`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `index.html`, `src/main.tsx`, `src/App.tsx` | 프론트 골격 |
| `docker-compose.yml`, `.env.example` | 로컬 MySQL |
| `.github/workflows/ci.yml`, `.github/dependabot.yml` | CI, SEC-16 |

- 설정값: `ARCHITECTURE.md` §6 키를 그대로 사용. 새 키 없음. `graffiti.moderation.ml.flag-threshold`는 "모델 버전별 값"이라 기본값 없이 선택값(nullable)으로 둔다.

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 기동 | `application.yml`에 잘못된 값(예: `countdown-threshold: 1.5`, 빈 palette) | 기동 실패, 어떤 키인지 로그 | `GraffitiPropertiesValidationTest` |
| 기동 | MySQL 접속 불가 | 기동 실패(조용히 H2로 대체하지 않음) | 통합 테스트는 Testcontainers로만 실행 |
| 요청 | 처리 안 된 예외 | 500 `INTERNAL`, 예외 메시지 미노출 | `GlobalExceptionHandlerTest` |
| 요청 | 잘못된 JSON 본문 | 400 `VALIDATION_FAILED` | 같은 테스트 |
| 운영 | `/actuator/env`, `/actuator/beans` 접근 | 404 | `ActuatorExposureIT` |
| CI | 비밀값이 커밋됨 | gitleaks 단계 실패 → PR 머지 불가 | CI 자체 (QA에서 가짜 키로 1회 확인, 커밋하지 않음) |
| 로컬 | Docker 없음 | `./gradlew test` 통합 테스트 실패(스킵하지 않음) — README에 요구사항 명시 | 해당 없음 |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1: Given 기본 `application.yml`, When 앱 컨텍스트가 뜨면, Then `GraffitiProperties`의 값이 `ARCHITECTURE.md` §6 기본값과 같다(대표 키: canvas 7680×4320, cell 48, chunk 480, threshold 0.70, countdown 6h, max-lifetime 7d, cooldown 30m, ml.mode SHADOW)
- [x] AC-2: Given 범위 밖 설정값, When 바인딩하면, Then 검증 실패로 기동이 중단된다
- [x] AC-3: Given MySQL(Testcontainers), When 앱이 뜨면, Then Flyway가 적용되고 `GET /actuator/health`가 200 `{"status":"UP"}`이며 `components` 등 상세가 없다
- [x] AC-4: When `GET /actuator/env`, Then 404 (SEC-10)
- [x] AC-5: Given 컨트롤러가 `ApiException(COOLDOWN, {retryAfterSec:720})`을 던지면, Then 429와 `{"error":{"code":"COOLDOWN","message":…,"details":{"retryAfterSec":720}}}`
- [x] AC-6: Given 처리 안 된 `RuntimeException("secret detail")`, Then 500 `INTERNAL`이고 본문에 "secret detail"이 없다 (SEC-13)
- [x] AC-7: `ErrorCode`의 코드와 HTTP 상태가 `API.md` §0 표 14개와 일치한다
- [x] AC-8: `npm run lint`가 `dangerouslySetInnerHTML` 사용 코드에서 실패한다 (SEC-12, 임시 파일로 확인 후 삭제)
- [x] AC-9: `npm test`, `npm run build`가 통과하고, `App`이 한국어 제목을 렌더링한다
- [x] AC-10: CI 워크플로가 backend test, frontend lint/test/build, gitleaks를 모두 실행한다
- [x] AC-11: 저장소에 `.env`가 없고 `.env.example`에는 값 없이 키만 있다 (SEC-06)
- [x] AC-12: `LocalDateTime.now()` 등 시스템 시계 직접 호출이 없다 (`Clock` 빈만 존재)

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | 설정 바인딩·검증 | `config/GraffitiPropertiesTest.java` (Spring `Binder` 사용, 컨텍스트 없이) |
| 단위 | 에러 매핑 | `common/error/GlobalExceptionHandlerTest.java` (`@WebMvcTest` + 테스트 전용 컨트롤러), `ErrorCodeTest.java` |
| 통합 | 기동, Flyway, health, actuator 노출 | `ApplicationIT.java` (`@SpringBootTest` + Testcontainers MySQL 8.4) |
| 프론트 | App 렌더링 | `src/App.test.tsx` |
| QA | `docker compose up -d mysql` → `bootRun` → `curl /actuator/health`, `npm run dev`에서 `/api` 프록시 동작 | 수동 체크리스트 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 기능 코드(엔티티, 컨트롤러, 보안 설정)를 넣지 않는다. 테스트 전용 컨트롤러는 `src/test`에만
- 의존성은 정확한 버전으로 고정한다(SEC-16). Spring Boot BOM이 관리하는 것은 BOM 버전으로 고정됨
- 숫자 설정값은 `application.yml`에만(INV-10). 테스트도 `GraffitiProperties`에서 읽는다

## 10. 롤백
코드와 CI 추가뿐이고 배포 대상이 아니다. 문제가 생기면 PR을 revert한다. `V0__init.sql`은 빈 마이그레이션이라 DB 되돌림이 필요 없다.

---

## 리뷰 기록 (/gf-review가 추가)
<!-- 날짜 · 커밋 SHA · CRITICAL n / INFO n · AUTO-FIX n · ASK 결과 · 상태 -->
- 2026-10-21 · `8eef82c` · CRITICAL 0 / INFO 4 · AUTO-FIX 1 · ASK 없음 · PASS
  - AUTO-FIX: `/actuator` 링크 목록이 200으로 열려 있어 `management.endpoints.web.discovery.enabled=false` (SEC-10, `ApplicationIT`가 잡음)
  - INFO-1: `graffiti.doodle.palette` 12색의 hex 값은 D-07에 색 이름만 있어 구현에서 정함. 디자인 확정 시 `application.yml`만 바꾸면 됨
  - INFO-2: Spring MVC의 405/415 등 API.md 표에 없는 4xx는 원래 HTTP 상태를 유지하고 코드는 `VALIDATION_FAILED`로 보냄
  - INFO-3: Flyway(Boot 3.5 관리 버전)가 MySQL 8.4를 "테스트되지 않음"으로 경고함. 마이그레이션과 기동은 정상. 8.0은 2026-04 EOL이라 8.4 LTS 유지
  - INFO-4: eslint-plugin-react가 ESLint 10을 지원하지 않아, `dangerouslySetInnerHTML` 금지는 코어 규칙 `no-restricted-syntax`로 구현하고 `src/eslintConfig.test.ts`로 고정

## QA 기록 (/gf-qa가 추가)
<!-- 날짜 · 커밋 SHA · 시나리오 통과/실패 · 발견 버그(ISSUE-NNN) · 상태 -->
- 2026-10-21 · `8eef82c` · 통과 · 버그 0 · PASS
  - 자동: backend `./gradlew test` 22개 통과(실패 0, 스킵 0, MySQL 8.4 Testcontainers 포함), frontend lint 통과, `npm test` 4개 통과, `npm run build` 통과
  - 수동: `docker compose up -d mysql` → healthy, 포트는 `127.0.0.1:3306`만 / `./gradlew bootRun` → `GET /actuator/health` = `{"status":"UP"}`, `/actuator/env` 404, `/api/nope` → `{"error":{"code":"NOT_FOUND",...}}` / `npm run dev` → `/`에 제목 "경희 낙서장", `/actuator/health`, `/api/*`가 8080으로 프록시됨
  - 미실행: gitleaks는 로컬에서 이미지를 받지 못해 CI 첫 실행에서 확인

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
