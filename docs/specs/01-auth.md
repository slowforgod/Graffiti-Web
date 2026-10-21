# 01-auth: 학교 Google 로그인, 세션, 동의, 익명 닉네임

- **상태**: MERGED (PR #17, 2026-10-21)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-21 (결정 필요 1은 추천안, 2는 OAuth 클라이언트 없으면 실제 로그인 QA만 미검증)
- **브랜치**: `feat/01-auth`
- **선행 명세**: 00

## 1. 왜 (Why)
낙서를 게시하고(06), 실시간으로 그리고(04, 05), 관리하는(10) 모든 기능은 "누가 요청했는가"를 세션에서 꺼낼 수 있어야 한다(SEC-02). 이 명세는 경희대 Google 계정으로만 로그인하게 하고, 첫 로그인에 동의와 익명 닉네임을 부여하며, 이후 기능이 쓸 인증·CSRF·권한 바탕을 만든다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-21 | Google OAuth(OIDC), 도메인 3중 검증, Spring Session JDBC 30일, 첫 로그인 동의 |
| 결정 | D-22 | 랜덤 닉네임 + 커서 색 부여, 이메일·작성자는 관리자 외 비노출 |
| 결정 | D-23 | `ADMIN_EMAILS` 환경변수로 관리자 지정 (화면, API 없음) |
| 결정 | D-38 | 동의 항목에 "부적절한 낙서 판별 모델 개선" 포함 |
| 결정 | D-02 | 같은 도메인 서빙 → 쿠키 세션, CORS 없음 |
| 불변 조건 | INV-10 | 세션 유지 기간, 닉네임 재시도 횟수 등 수치는 `GraffitiProperties` |
| 보안 | SEC-02 | 사용자 식별은 세션에서만. 요청 본문의 id 무시 |
| 보안 | SEC-03 | CSRF(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더), 세션 쿠키 `HttpOnly`·`Secure`·`SameSite=Lax` |
| 보안 | SEC-04 | `hd == khu.ac.kr`, `email_verified == true`, 이메일 `@khu.ac.kr` 접미사 **셋 다** |
| 보안 | SEC-06 | `GOOGLE_CLIENT_ID/SECRET`은 환경변수로만 |
| 보안 | SEC-13 | 동의 전 게시 불가(`CONSENT_REQUIRED`), 로그에 이메일·토큰·세션 ID 금지, 공개 응답에 이메일 없음 |
| API | `API.md` §1 | `/oauth2/authorization/google`, `/login/oauth2/code/google`, `POST /api/auth/logout`, `GET /api/me`, `POST /api/me/consent` 구현. 에러 `UNAUTHENTICATED`, `CSRF_INVALID`, `DOMAIN_NOT_ALLOWED`, `CONSENT_REQUIRED` |
| DB | `DB.md` §2 | `users` 테이블, Spring Session JDBC 테이블 마이그레이션 추가 |

## 3. 범위
**포함**
- 백엔드 `auth/`, `user/`, `config/SecurityConfig`
  - Spring Security + `spring-boot-starter-oauth2-client`(Google OIDC). 로그인 성공 직후 `KhuDomainVerifier`(순수 로직)로 SEC-04 세 조건 확인. 실패 시 계정 생성 없음, 세션 무효화, `/?loginError=DOMAIN_NOT_ALLOWED`로 리다이렉트
  - 첫 로그인: `users` 행 생성(`google_sub` 기준 find-or-create), 닉네임 "익명의 {동물} #{4자리}"와 커서 색 부여, 유니크 충돌 시 재시도
  - 매 로그인: `ADMIN_EMAILS`에 있으면 `role=ADMIN`, 없으면 `USER`로 동기화 (목록에서 빠지면 다음 로그인부터 일반 사용자)
  - Spring Session JDBC, 세션 유지 30일, 쿠키 이름 `SESSION`
  - CSRF: `CookieCsrfTokenRepository`(쿠키 `XSRF-TOKEN`, JS에서 읽기 가능) + Spring Security SPA 방식 헤더 검증. 실패 시 403 `CSRF_INVALID`(API.md 형식)
  - `/api/**` 비인증 접근은 리다이렉트가 아니라 401 `UNAUTHENTICATED` JSON
  - 권한 표기 구현: `공개` / `로그인`(+동의) / `작성자`(+차단 아님) / `관리자`. 이후 명세가 쓸 수 있게 `@CurrentUser` 인자 해석과 권한 검사(동의, 차단)를 한 곳에 둔다
  - `GET /api/me`, `POST /api/me/consent`, `POST /api/auth/logout`
- 프론트
  - `api/client.ts`: fetch 래퍼, 변경 요청에 `X-XSRF-TOKEN` 자동 첨부, API 에러 형식 파싱
  - 앱 시작 시 `GET /api/me`, 로그인/로그아웃 버튼(임시 상단 영역), 동의 화면(첫 로그인), `?loginError=DOMAIN_NOT_ALLOWED` 안내 문구
- 설정: `graffiti.auth.session-timeout`(30d), `graffiti.auth.nickname-max-attempts`(10) 추가 → `ARCHITECTURE.md` §6 표 갱신
- 문서: `ARCHITECTURE.md` §4에 로그인 흐름 추가, `DB.md`에 세션 테이블 명시

**제외 (NOT in scope)**
- "펜을 처음 잡을 때 로그인" 유도 UX → 05-draft (지금은 버튼으로만 로그인)
- 차단·해제 기능 → 10-admin (이 명세는 `banned_at` 읽기와 `작성자` 권한 검사만)
- IP당 속도 제한(SEC-09) → nginx `limit_req`(12a)와 11-security-pass. 01의 공개 엔드포인트는 `GET /api/me` 하나
- STOMP 인증 → 04-realtime (세션을 같이 쓰도록 설계만 맞춤)
- 쿨다운 기록 → 06. `/api/me`의 `cooldownEndsAt`은 `last_posted_at`이 있으면 계산, 지금은 항상 `null`

## 4. 현재 상태 (검증된 사실)
- 00-bootstrap(PR #3) 기준: `GraffitiProperties`에 `auth.allowed-domain`, `auth.admin-emails`, `web.allowed-origins` 있음. `ErrorCode`에 필요한 코드 전부 있음. Spring Security 없음, 테이블 없음(`V0__init.sql`만)
- 프론트는 빈 `App` 하나

## 5. 설계

```
로그인
 브라우저 ─▶ /oauth2/authorization/google ─▶ Google ─▶ /login/oauth2/code/google
   Spring Security가 ID 토큰 검증(서명, aud, exp, nonce)
   └─▶ OidcUserService 래퍼:
         KhuDomainVerifier.verify(claims)  ── 실패 ─▶ OAuth2AuthenticationException
         │                                            └─ failureHandler: 세션 무효화, /?loginError=DOMAIN_NOT_ALLOWED
         └ 통과 ─▶ UserAccountService.loginOrRegister(sub, email)   (트랜잭션)
                     find by google_sub ─ 없으면 INSERT(닉네임, 색) ─ 유니크 충돌 시 재조회/재시도
                     role = ADMIN_EMAILS 포함 여부
                   ─▶ 세션 principal = { userId, role }   (이메일은 세션에 두지 않음)
   successHandler ─▶ redirect "/"

요청 처리
 /api/** ─ SecurityFilterChain: CSRF(변경 요청) → 인증 필요 경로면 세션 확인(401) → 컨트롤러
          @CurrentUser AuthUser ─ DB의 최신 consent/banned/role 반영 (세션 값만 믿지 않음, SEC-15 대비)
```
큐(INV-01)를 거치는 상태 변경은 없다. 사용자 행 생성과 동의 기록은 페이지 상태가 아니다.

| 파일 (예정) | 역할 |
|---|---|
| `auth/KhuDomainVerifier.java` | SEC-04 순수 검증 |
| `auth/KhuOidcUserService.java` | 검증 + 가입/로그인 연결 |
| `auth/LoginFailureHandler.java`, `LoginSuccessHandler.java` | 리다이렉트 |
| `auth/AuthUser.java`, `CurrentUser.java`, `CurrentUserArgumentResolver.java` | 세션 사용자, 권한 검사 |
| `auth/MeController.java` | `/api/me`, `/api/me/consent`, `/api/auth/logout` |
| `user/User.java`, `UserRepository.java`, `UserAccountService.java` | 엔티티, find-or-create, 역할 동기화 |
| `user/NicknameGenerator.java` | 순수 로직(Random 주입) |
| `config/SecurityConfig.java` | 필터 체인, CSRF, 401/403 JSON |
| `db/migration/V1__users.sql`, `V2__spring_session.sql` | 스키마 |
| `frontend/src/api/client.ts`, `src/auth/*` | API 클라이언트, 로그인 상태, 동의 화면 |

- 설정값(INV-10): `graffiti.auth.session-timeout: 30d`, `graffiti.auth.nickname-max-attempts: 10`
- 커서 색: **추천: 팔레트(`graffiti.doodle.palette`)에서 흰색을 뺀 11색 중 무작위** — 새 설정 없이 D-07 색과 어울림. (대안: 별도 커서 색 목록 설정 추가)
- 닉네임 동물 목록은 코드 상수(한국어 단어 목록, 숫자 아님)

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 로그인 | 개인 Gmail, `hd` 누락, `email_verified=false`, `x@khu.ac.kr.evil.com` | 계정 생성 없음, 세션 무효화, 첫 화면에 "경희대학교 계정으로만 로그인할 수 있어요." | `KhuDomainVerifierTest` 4케이스 + `LoginFlowIT` |
| 로그인 | 같은 사용자가 두 탭에서 동시에 첫 로그인 | 유니크 제약으로 한 행만 생성, 다른 쪽은 재조회 | `UserAccountServiceIT` 동시 실행 |
| 로그인 | 닉네임 충돌 | 재시도(최대 설정값), 모두 실패하면 500 `INTERNAL` 로그 | `NicknameGeneratorTest`, 충돌 강제 IT |
| 로그인 | Google 장애, 사용자가 취소 | Spring 기본 실패 → `/?loginError=...` 아님, 일반 실패 문구 | 실패 핸들러 단위 테스트 |
| 요청 | CSRF 토큰 없음/불일치 | 403 `CSRF_INVALID` | `SecurityIT` |
| 요청 | 세션 만료 후 `/api/me/consent` | 401 `UNAUTHENTICATED` | `SecurityIT` |
| 요청 | 동의 전 `작성자` 권한 경로 | 403 `CONSENT_REQUIRED` (테스트 전용 엔드포인트로 검증) | `AuthorizationIT` |
| 요청 | 로그인 중 관리자 목록에서 제외됨 | 다음 요청부터 DB 역할 기준으로 판정 | `AuthorizationIT` |
| 로그 | 로그인 처리 로그 | userId만 기록, 이메일·토큰·세션 ID 없음 | 로그 캡처 테스트(`OutputCaptureExtension`) |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1: Given `hd=khu.ac.kr`, `email_verified=true`, `a@khu.ac.kr`, When 로그인, Then `users` 1행 생성, 닉네임이 "익명의 … #dddd" 형식, 커서 색이 `#RRGGBB`, `/`로 리다이렉트
- [x] AC-2: 같은 사용자의 두 번째 로그인은 행을 새로 만들지 않고 닉네임·색이 그대로다
- [x] AC-3 (SEC-04): `hd` 누락, `hd=gmail.com`, `email_verified=false`, `x@khu.ac.kr.evil.com` 각각 계정이 생기지 않고 `/?loginError=DOMAIN_NOT_ALLOWED`로 간다
- [x] AC-4: 비로그인 `GET /api/me` → 200 `{"authenticated":false}`
- [x] AC-5: 로그인 `GET /api/me` → API.md §1 형식, 이메일 필드 없음(SEC-13)
- [x] AC-6: `POST /api/me/consent` 후 `consentRequired=false`, 두 번 호출해도 최초 시각 유지
- [x] AC-7 (SEC-03): CSRF 헤더 없는 `POST /api/me/consent` → 403 `CSRF_INVALID` / 세션 쿠키가 `HttpOnly; Secure; SameSite=Lax`
- [x] AC-8: 비로그인 `POST /api/auth/logout`, `POST /api/me/consent` → 401 `UNAUTHENTICATED` (리다이렉트 아님)
- [x] AC-9: `ADMIN_EMAILS`에 있는 사용자는 `role=ADMIN`, 목록에서 빠지면 다음 로그인에 `USER`
- [x] AC-10: 동의 전 사용자가 `작성자` 권한 경로 호출 → 403 `CONSENT_REQUIRED`, 차단 사용자 → 403 `BANNED`
- [x] AC-11 (SEC-02): 요청 본문에 `userId`를 넣어도 세션 사용자로 처리된다(동의 엔드포인트로 검증)
- [x] AC-12 (SEC-13): 로그인 성공·실패 로그에 이메일, ID 토큰, 세션 ID가 없다
- [x] AC-13: 세션이 MySQL `SPRING_SESSION`에 저장되고 앱 재시작 후에도 유지된다
- [x] AC-14 (프론트): 동의 전이면 동의 화면이 보이고, 동의하면 사라진다 / `loginError=DOMAIN_NOT_ALLOWED`면 한국어 안내가 보인다 / 변경 요청에 `X-XSRF-TOKEN`이 붙는다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | 도메인 검증, 닉네임 생성 | `KhuDomainVerifierTest`, `NicknameGeneratorTest` |
| 통합 | 로그인 흐름(`oidcLogin()` + 가짜 ID 토큰 클레임), 보안 필터, 권한, 세션 저장 | `LoginFlowIT`, `SecurityIT`, `AuthorizationIT`, `UserAccountServiceIT` (MySQL Testcontainers) |
| 프론트 | API 클라이언트 CSRF, 동의 화면, 로그인 에러 문구 | `api/client.test.ts`, `auth/*.test.tsx` |
| QA | 실제 Google 로그인: 학교 계정 성공, 개인 Gmail 거절, 로그아웃, 브라우저 재시작 후 세션 유지 | 수동 체크리스트 (**Google OAuth 클라이언트 필요**) |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- SEC-04는 세 조건을 각각 테스트한다. `endsWith("khu.ac.kr")`처럼 `@` 없는 비교 금지
- 세션 principal에 이메일을 넣지 않는다. 이메일이 필요한 곳(관리자)은 DB에서 읽는다
- `csrf().disable()`, `permitAll()` 남용 금지. 공개 경로는 API.md 표의 `공개`만
- 동의 문구는 SEC-13 항목(이메일과 작성한 낙서, 인증과 제재, 판별 모델 개선(D-38), 보관 기간)을 담은 초안으로 넣고, 학교 확인 전 최종본이 아님을 명세 QA 기록에 남긴다

## 10. 롤백
PR revert. `V1__users.sql`, `V2__spring_session.sql`은 새 테이블만 추가하므로, 되돌릴 때는 다음 마이그레이션에서 `DROP TABLE`을 하지 말고 그대로 둔다(INV-07 정신, 데이터 보존). 스테이징 배포 전이면 DB를 새로 만들면 된다.

## 결정 필요 (승인 시 함께 확정)
1. 커서 색 출처 — 추천: 팔레트에서 흰색 제외 11색
2. 실제 로그인 QA용 Google OAuth 클라이언트(`GOOGLE_CLIENT_ID/SECRET`) — 팀에서 Google Cloud 콘솔에 만들어 로컬 `.env`에 넣어야 함. 없으면 QA의 실제 로그인 항목만 "미검증"으로 남기고 나머지는 진행

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-21 · `7647971` · CRITICAL 0 / INFO 5 · AUTO-FIX 3 · ASK 없음 · PASS
  - AUTO-FIX 1: `application.yml`에 `server:` 블록을 넣으면서 `spring.jackson.time-zone`이 `server.jackson` 아래로 밀려 무시되던 것을 되돌림
  - AUTO-FIX 2: Vite 프록시 문자열 축약형은 `changeOrigin: true`라서 OAuth `redirect_uri`가 `localhost:8080`으로 만들어졌음. `/api`, `/oauth2`, `/login`은 Host를 유지하도록 바꿈 (curl로 `redirect_uri=http://localhost:5173/login/oauth2/code/google` 확인)
  - AUTO-FIX 3: `@WebMvcTest`가 새 `@CurrentUser` 해석기를 읽어 `GlobalExceptionHandlerTest` 컨텍스트가 깨짐 → 해당 테스트는 보안 필터를 끄고 `UserAccountService`를 mock (에러 본문만 검사하는 테스트이고 보안은 `SecurityIT`가 검사)
  - INFO-1: Spring Security 6.5.11에는 `csrf().spa()`가 없어서 레퍼런스 문서의 SPA 방식(`SpaCsrfTokenRequestHandler`)을 그대로 옮김
  - INFO-2: 세션에는 `SessionUser(userId)`만 저장하고 Google 액세스 토큰도 저장하지 않음(`OAuth2AuthorizedClientRepository` no-op). `SecurityIT`가 `SPRING_SESSION_ATTRIBUTES`에 이메일과 sub가 없음을 확인
  - INFO-3: 권한은 `@CurrentUser(Access)`가 매 요청 DB에서 판정. URL 규칙은 "`/api/**`는 세션 필요, `GET /api/me`만 공개" 하나뿐이고, 나머지 경로(OAuth, health, error, 정적 파일)는 `permitAll`
  - INFO-4: spring-security-test의 `csrf()`는 공유 컨텍스트의 CSRF 저장소를 바꿔서 다른 테스트를 깨뜨림 → `AuthorizationIT`는 실제 쿠키·헤더 이중 제출을 사용
  - INFO-5: `npm audit`의 vitest 계열 critical 2건은 main에도 있던 개발 의존성 문제(이 PR과 무관). Dependabot 업데이트로 처리 예정

## QA 기록 (/gf-qa가 추가)
- 2026-10-21 · `7647971` · 통과 · 버그 1(위 AUTO-FIX 2) · PASS (실제 Google 로그인은 미검증)
  - 자동: backend `./gradlew test` 109개 통과(실패 0, 스킵 0), frontend `npm test` 14개 통과, `npm run lint`, `npm run build` 통과. 라인 커버리지: `KhuDomainVerifier`, `NicknameGenerator`, `UserAccountService`, 로그인 핸들러 100%, `CurrentUserArgumentResolver` 95%, `MeController` 89%(쿨다운 계산은 06에서 `last_posted_at`이 생긴 뒤)
  - 수동(로컬 MySQL + `bootRun`, curl): `GET /api/me` → `{"authenticated":false}`와 `XSRF-TOKEN` 쿠키(`Secure; SameSite=Lax`, HttpOnly 아님) / `/oauth2/authorization/google` → Google로 302, `hd=khu.ac.kr`, `nonce` 포함 / CSRF 없는 `POST /api/me/consent` → 403 `CSRF_INVALID` / 비로그인 로그아웃 → 401 `UNAUTHENTICATED` / 잘못된 state로 콜백 → `/?loginError=LOGIN_FAILED` / `/login` 기본 로그인 페이지 없음(404)
  - AC-3, AC-12의 로그인 경로는 `LoginFlowIT`가 실제 Google 등록 정보와 DB로 `KhuOidcUserService`와 핸들러를 실행해서 확인. ID 토큰 서명 검증 자체는 Spring Security 몫이라 테스트하지 않음
  - AC-13: 세션이 `SPRING_SESSION`에 30일 만료로 저장되는 것은 `SecurityIT`로 확인. 앱 재시작 후 유지는 저장 위치로 판단했고 실제 재시작은 Google 로그인 QA 때 함께 확인
  - 미검증: 실제 Google 로그인(학교 계정 성공, 개인 Gmail 거절, 로그아웃, 브라우저 재시작 후 유지). `GOOGLE_CLIENT_ID/SECRET`이 있어야 함. 승인된 리디렉션 URI: 로컬 `http://localhost:5173/login/oauth2/code/google`
  - 동의 문구는 초안이다. 보관 기간을 "서비스 운영 기간 동안 보관 (학교 확인 후 확정)"으로 넣었고, 공개 전 학교 개인정보 담당 부서 확인이 필요(SEC-13, D-38)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
