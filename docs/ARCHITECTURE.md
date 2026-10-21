# 아키텍처

> 결정의 이유는 `docs/DECISIONS.md`(D-xx)에 있습니다. 이 문서는 **어떻게 동작하는지**와 **절대 깨면 안 되는 불변 조건(INV-xx)**을 정의합니다.
> 흐름을 바꾸면 이 문서의 다이어그램도 **같은 커밋에서** 고칩니다.

---

## 1. 시스템 구성

```
                         https://<도메인>  (EC2 1대, Docker Compose)
 ┌────────────┐        ┌─────────────────────────────────────────────────────────────┐
 │ 브라우저    │  HTTPS │ nginx (TLS, Let's Encrypt, IP당 limit_req, 정적 파일 서빙)     │
 │ React+Konva│───────▶│   /            → frontend 정적 파일 (Vite build)              │
 │            │  WSS   │   /api/*, /oauth2/*, /login/*, /actuator/health → app:8080     │
 │            │───────▶│   /ws          → app:8080 (WebSocket upgrade)                 │
 └────────────┘        │                                                               │
                       │ app (Spring Boot, JVM 1개)                                     │
                       │   ├─ REST 컨트롤러 ─┐                                          │
                       │   ├─ STOMP (SimpleBroker, /topic, /user/queue)                │
                       │   ├─ PageCommandProcessor ◀── 단일 스레드 큐 (INV-01)          │
                       │   │     └─ PageState (메모리: cellCount[], 상태, 타이머)        │
                       │   ├─ ModerationService ── 큐 밖 스레드풀 (INV-02)              │
                       │   │     ├─ WordFilter (금칙어)                                │
                       │   │     ├─ KhuChatProvider ───────────▶ KHUChat API (텍스트)    │
                       │   │     ├─ OpenAiModerationProvider ──▶ OpenAI omni-moderation │
                       │   │     └─ MlModerationProvider ──┐  (그림, SHADOW/ENFORCE)    │
                       │   └─ DoodleRenderer (Java2D → PNG) │                           │
                       │                                    ▼ POST /v1/moderate (내부망) │
                       │ ml (Python FastAPI, CPU, mem_limit)                           │
                       │   ├─ 이미지 분류 (CLIP 제로샷 → 미세조정 모델)                   │
                       │   └─ OCR (손글씨 → 글자)                                       │
                       │   모델 가중치: S3에서 버전별로 받아 컨테이너 시작 시 로드          │
                       │                                                               │
                       │ mysql:8 (Docker 내부 네트워크만, 외부 포트 없음)                 │
                       └─────────────────────────────────────────────────────────────┘
                               ▲ 매일 mysqldump → S3 (SEC-11)
 Google OAuth ◀── 로그인 리다이렉트 (hd=khu.ac.kr 검증, SEC-04)
```

## 2. 백엔드 패키지 구조

기능별로 패키지를 나눕니다. 패키지 하나에 controller / service / domain / repository를 함께 둡니다.

```
backend/src/main/java/kr/ac/khu/graffiti/
├── config/        설정 바인딩(GraffitiProperties), SecurityConfig(필터 체인, SPA CSRF, 401/403 JSON), WebSocket
├── auth/          KhuDomainVerifier(SEC-04 순수 로직), KhuOidcUserService, 로그인 성공·실패 핸들러,
│                  SessionUser(세션에는 userId만), @CurrentUser + Access(SIGNED_IN/LOGIN/AUTHOR/ADMIN), MeController
├── user/          User 엔티티, UserAccountService(첫 로그인 가입, 역할 동기화, 동의), NicknameGenerator(순수 로직)
├── page/          ★ 핵심 도메인
│   ├── PageCommandProcessor (인터페이스) + SingleThreadPageCommandProcessor
│   │                        커맨드: ApplyFootprint, EndPage, GetPageSnapshot, EnsureLivePage, InstallPage
│   ├── PageState            메모리 상태 (cellCount, status, scheduledEndAt)
│   ├── Footprint            선분 bbox(굵기 포함)/텍스트 박스 → 칸 집합 (순수 로직)
│   ├── OccupancyGrid        참조 카운트, 점유율, 비트맵 (순수 로직)
│   ├── PageLifecycle        상태 전이, 종료 시각 계산 (순수 로직)
│   ├── PageTimer            종료 예약 (PageStatusChanged마다 재예약)
│   ├── PageBootstrap        재시작 복구 (웹 서버 시작 전)
│   └── event/               PageStatusChanged, OccupancyChanged, PageTurned (커밋 후 발행 → 04가 STOMP로 중계)
├── doodle/        Doodle 엔티티, DoodleValidator(순수 로직), 게시/삭제 유스케이스, 청크 조회
├── moderation/    ModerationProvider 인터페이스, 구현체 4개(WordFilter, KhuChat, OpenAi, Ml), DoodleRenderer,
│                  ModerationService(병렬 호출과 판정 합성), MlPredictionRecorder
├── realtime/      STOMP 설정, ChannelInterceptor(인증·구독 검증·속도 제한), Broadcaster 인터페이스
├── admin/         관리자 API, moderation_action 기록
└── common/        에러 응답 형식(ApiError), 예외 → 에러 코드 매핑, 시계(Clock) 주입
```

- **순수 로직**(`OccupancyGrid`, `PageLifecycle`, `DoodleValidator`, 쿨다운 판정, `ModerationVerdictCombiner`)은 Spring에 의존하지 않는 클래스로 만들어 단위 테스트합니다.

### ML 서버 구조 (`ml/`)

```
ml/
├── serving/       FastAPI 추론 서버 (POST /v1/moderate, GET /health)
│   ├── app/       라우터, 이미지 분류기, OCR, 응답 스키마
│   ├── tests/     pytest (스키마, 시간 제한, 잘못된 입력)
│   └── Dockerfile
├── training/      미세조정 스크립트 (운영 데이터 추출본 사용, 작성자 정보 없음)
├── eval/          평가 세트 메타데이터, 지표 계산 스크립트 (D-39)
└── models.lock    사용할 모델 버전 이름과 S3 경로, 체크섬 (가중치 파일은 git에 없음)
```
- 현재 시각은 항상 주입받은 `java.time.Clock`으로 얻습니다. 타이머와 쿨다운 테스트에서 시간을 조작하기 위해서예요.

## 3. 프론트엔드 구조

```
frontend/src/
├── api/           REST 클라이언트(CSRF 헤더 자동 첨부), 에러 코드 타입
├── auth/          /api/me 상태, 로그인·로그아웃 바, 동의 화면, 로그인 실패 안내
├── realtime/      STOMP 클라이언트, 청크 구독 관리자(구독 → 조회 → 병합, INV-09)
├── canvas/        Konva Stage, 뷰포트(팬/줌, 0.5~4배), 청크 계산, 청크별 레이어 캐시
├── tools/         펜, 텍스트, 지우개(초안 전용), 선택, 이동 + 초안 상태 머신
├── ui/            툴바, 상단 상태바(점유율, 남은 시간), T−5분 카운트다운, 미니맵, 토스트
├── admin/         관리자 화면
└── shared/        설정 상수(서버의 /api/pages/current 응답으로 받음), 타입
```

- 확정된 doodle은 **청크별 Konva 레이어에 캐시**해서 그립니다. 초안과 커서는 별도의 동적 레이어에 둡니다.
- 사용자 입력 텍스트는 Konva로 캔버스에 그리고, HTML로 넣지 않습니다(SEC-12).

## 4. 핵심 흐름

### 4.1 게시 (✓)

```
Client                         REST(Controller)          PageCommandProcessor(큐)      ModerationService(큐 밖)
  │ POST /api/pages/{p}/doodles   │                              │                             │
  │──────────────────────────────▶│ ① 인증·CSRF·동의·크기(2MB)    │                             │
  │                               │ ② DoodleValidator (순수 검증) │                             │
  │                               │──── ReservePost(user,p) ────▶│ ③ p가 현재 페이지? 차단?     │
  │                               │                              │    쿨다운 남음? → 거절        │
  │                               │                              │    last_posted_at = now      │
  │                               │◀──────── reserved ───────────│                             │
  │                               │──────────────── moderate(doodle) ──────────────────────────▶│ ④ 텍스트: 금칙어→KHUChat→OpenAI
  │                               │                              │                             │    그림: Java2D PNG→ OpenAI ∥ ML(4.7)
  │                               │◀─────────────── PASSED / REJECTED / UNCHECKED ──────────────│   (타임아웃 → UNCHECKED)
  │                               │── PublishDoodle / RecordReject ▶│ ⑤ p가 아직 현재 페이지?     │
  │                               │                              │    아니면 PAGE_ENDED +        │
  │                               │                              │    쿨다운 되돌림 (D-28)       │
  │                               │                              │    INSERT doodle,doodle_chunk │
  │                               │                              │    cellCount += 칸들          │
  │                               │                              │    70% 넘으면 COUNTDOWN 시작  │
  │                               │                              │    broadcast doodle:published │
  │                               │                              │    broadcast page:status      │
  │◀── 201 {doodle} / 422 MODERATION_REJECTED / 429 COOLDOWN / 409 PAGE_ENDED ───────────────────│
```

- ③과 ⑤는 큐 안에서, ④는 큐 밖에서 실행합니다(INV-01, INV-02).
- 거부되면 `RecordReject`가 `status=REJECTED`로 저장하고 `moderation_action(AI, REJECT)`를 기록합니다. 쿨다운은 그대로 소모됩니다(D-12).
- DB 쓰기는 큐 작업 안에서 트랜잭션 하나로 묶고, 커밋이 성공한 **뒤에** 메모리 상태 갱신과 브로드캐스트를 합니다. DB가 실패하면 메모리는 그대로입니다.

### 4.2 화면 이동과 초기 로딩 (INV-09)

```
뷰포트 변경 → 보이는 청크 집합 계산 (최대 60개)
   ├─ 새로 보이는 청크: ① STOMP SUBSCRIBE /topic/page.{p}.chunk.{i}
   │                       (도착하는 이벤트는 청크별 대기열에 쌓음)
   │                    ② GET /api/pages/{p}/doodles?chunks=i,j,...
   │                    ③ 응답 + 대기열 병합: doodle ID로 중복 제거,
   │                       대기열의 doodle:removed는 응답에서도 제거
   └─ 벗어난 청크:     UNSUBSCRIBE, 해당 청크에만 있던 doodle은 메모리에서 해제
```

### 4.3 초안 중계 (저장 안 함, INV-06)

```
펜 다운 → draftId(UUID) 생성 → SEND /app/draft {op:"start"}
그리는 중 50ms마다 → SEND /app/draft {op:"points", points:[...], bbox}
서버: 인증·차단·속도 제한(초당 20회)·bbox 상한 검사 → bbox와 겹치는 청크 토픽으로 중계 (큐를 거치지 않음)
✗ / 10분 무동작 / 연결 끊김 → draft:cancel 브로드캐스트 (연결 끊김은 서버가 세션 종료 이벤트에서 발행)
✓ → REST 게시(4.1). 성공하면 클라이언트가 draft:cancel을 보내 반투명 초안을 지움
```

### 4.4 페이지 넘김

```
PageTimer (scheduled_end_at 도달) ──▶ 큐: EndPage(pageId)   (현재 페이지가 아니면 무시, 아직 이르면 재예약)
  ① UPDATE page SET status=ENDED, ended_at, end_reason, final_occupancy_permille,
     final_occupancy_bitmap
  ② INSERT 새 page (ACTIVE, 현재 설정값 스냅샷)
  ③ PageState 교체 (cellCount 새 배열)
  ④ 커밋 후 이벤트 PageTurned(old, new), PageStatusChanged(new) → broadcast /topic/page.{old}.meta  page:turned {newPageId}
클라이언트: 초안 폐기 → 옛 페이지 구독 해제 → /api/pages/current → 새 페이지 로딩(4.2)
```

- 상태 전이는 `UPDATE ... WHERE id=? AND status=?`처럼 **이전 상태를 조건으로** 실행합니다. 영향받은 행이 0이면 예외를 던집니다.
- T−5분 카운트다운은 클라이언트가 `scheduledEndAt`과 서버 시각 차이(`serverTime`)로 계산합니다. 서버 푸시는 필요 없습니다.

### 4.4a 로그인 (D-21, SEC-04, SEC-13)

```
브라우저 ─▶ /oauth2/authorization/google  (hd=khu.ac.kr 힌트) ─▶ Google ─▶ /login/oauth2/code/google
  Spring Security: ID 토큰 서명, iss, aud, exp, nonce 검증
  KhuOidcUserService (userinfo 호출 없음, ID 토큰 클레임만 사용)
    KhuDomainVerifier: hd == khu.ac.kr && email_verified == true && email이 "@khu.ac.kr"로 끝남
      실패 ─▶ LoginFailureHandler: 세션 무효화, /?loginError=DOMAIN_NOT_ALLOWED (그 밖의 실패는 LOGIN_FAILED)
    UserAccountService.loginOrRegister(sub, email): google_sub로 조회, 없으면 닉네임·커서 색과 함께 INSERT
      유니크 충돌(동시 첫 로그인, 닉네임 중복)은 새 트랜잭션으로 재시도 (최대 nickname-max-attempts)
      role = ADMIN_EMAILS 포함 여부로 매 로그인 동기화 (D-23)
  LoginSuccessHandler: 인증 객체를 SessionUser(userId)만 담은 것으로 교체해 세션에 저장, / 로 리다이렉트
     (ID 토큰, 이메일, Google 액세스 토큰은 세션에 남지 않음)

요청: SecurityFilterChain ─ CSRF(변경 요청, XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)
      ─ /api/** 는 세션 필요(없으면 401 JSON, GET /api/me만 공개)
      ─ 컨트롤러의 @CurrentUser(Access): 매 요청 DB에서 동의, 차단, 역할을 읽어 판정
```

- 세션은 Spring Session JDBC(`SPRING_SESSION`)에 저장하고 쿠키 `SESSION`은 `HttpOnly; Secure; SameSite=Lax`, 유지 기간은 `graffiti.auth.session-timeout`입니다.
- 로컬 개발에서 Google OAuth 클라이언트의 승인된 리디렉션 URI는 `http://localhost:5173/login/oauth2/code/google`입니다(Vite 프록시가 Host를 유지).

### 4.5 서버 시작과 재시작 복구 (D-25)

```
PageBootstrap (SmartLifecycle, 웹 서버보다 앞 단계)
1. 큐: EnsureLivePage — 비종료(ACTIVE/COUNTDOWN) 페이지 조회 → 없으면 새로 생성
2. 큐 밖: PublishedFootprintSource로 그 페이지의 PUBLISHED doodle을 읽어 OccupancyGrid 재계산 (INV-02)
3. 큐: InstallPage — 메모리 상태 설치, PageStatusChanged → PageTimer 예약
4. scheduled_end_at이 지났으면 즉시 EndPage
5. 그 다음에 웹 서버가 HTTP와 STOMP 요청을 받기 시작
```

### 4.6 삭제, 숨김, 복구

| 작업 | 주체 | 상태 변화 | 점유율 | 브로드캐스트 |
|---|---|---|---|---|
| 내 doodle 삭제 | 작성자 | PUBLISHED → DELETED | −칸 | doodle:removed |
| 관리자 삭제 | 관리자 | PUBLISHED → DELETED | −칸 (현재 페이지일 때만) | doodle:removed |
| 차단과 함께 일괄 숨김 | 관리자 | PUBLISHED → HIDDEN | −칸 | doodle:removed |
| 거부/미검사 복구 | 관리자 | REJECTED → PUBLISHED (UNCHECKED는 이미 PUBLISHED, 표시만 해제) | +칸 (현재 페이지일 때만) | doodle:published |

모두 큐를 거치고(INV-01), 행은 지우지 않습니다(INV-07).

### 4.7 그림 검사와 자체 ML 모델 (D-34 ~ D-40)

```
그림 doodle ─▶ DoodleRenderer (PNG, 큐 밖)
                 ├──────────────▶ OpenAI omni-moderation (이미지)        ─┐
                 └──────────────▶ ML 서버 POST /v1/moderate (제한 3초)    │  병렬
                                   ├─ 이미지 분류 → label, scores        │
                                   └─ OCR → ocrText                      │
                                         └─▶ 텍스트 검사 경로             │
                                             (금칙어 → KHUChat → OpenAI) ─┤
                                                                          ▼
                              ModerationVerdictCombiner (순수 로직)
                              ├─ OpenAI 이미지 결과: 항상 반영
                              ├─ ML 이미지 판정 + OCR 텍스트 검사 결과:
                              │    mode=SHADOW  → 기록만 (최종 판정에 영향 없음, INV-13)
                              │    mode=ENFORCE → 하나라도 flagged면 REJECTED
                              │    ML 장애/시간 초과 → ML 판정만 빠진 것으로 처리
                              └─ OpenAI 장애/시간 초과 → UNCHECKED (D-18)
                                          │
                              MlPredictionRecorder → ml_prediction INSERT (큐 밖, 실패해도 게시에 영향 없음)
```

- 모드는 설정값 `graffiti.moderation.ml.mode`(`OFF` / `SHADOW` / `ENFORCE`)로 바꿉니다. `ENFORCE` 전환은 D-39 기준을 넘고 팀이 승인한 뒤에만 합니다.
- `ml_prediction`은 평가용 기록이라 게시 트랜잭션과 분리합니다. 기록이 실패해도 게시 결과는 바뀌지 않습니다.
- **학습 데이터 추출**: 관리자 판정이 있는 doodle의 payload를 다시 렌더링해서 이미지와 라벨만 내보냅니다. `author_id`, 닉네임, 이메일은 포함하지 않습니다(D-38, SEC-17).

## 5. 불변 조건 (INV) — 절대 깨면 안 됨

| ID | 규칙 | 근거 |
|---|---|---|
| **INV-01** | 페이지 상태(`cellCount`, 페이지 상태, 쿨다운, doodle 게시·삭제·숨김·복구)를 바꾸는 모든 작업은 `PageCommandProcessor`를 거친다. 그 밖에서 변경하지 않는다 | D-03 |
| **INV-02** | 큐 안에서 느린 작업(AI 호출, 이미지 렌더링, 외부 HTTP, 대량 조회)을 하지 않는다. 큐 작업 하나는 수 ms 안에 끝나야 한다 | D-03 |
| **INV-03** | 페이지 상태는 `ACTIVE → COUNTDOWN → ENDED` 한 방향. 카운트다운은 취소되지 않는다. 종료 시각 = `min(created_at + 7일, countdown_started_at + 6시간)`. 전이는 이전 상태를 조건으로 하는 UPDATE로 | D-15 |
| **INV-04** | 모든 쓰기 요청과 STOMP 전송은 `pageId`를 포함하고, 현재 페이지가 아니면 `PAGE_ENDED`로 거절한다 | D-16 |
| **INV-05** | doodle은 모더레이션 결과가 `PASSED` 또는 `UNCHECKED`일 때만 게시된다. 검사를 건너뛰는 경로는 없다 | D-18 |
| **INV-06** | 초안은 DB나 디스크에 저장하지 않는다. 메모리에서도 중계만 하고 보관하지 않는다 | D-11 |
| **INV-07** | doodle과 page 행은 삭제하지 않는다(상태만 바꿈). 종료된 페이지 데이터도 보관한다 | D-17 |
| **INV-08** | 쿨다운은 큐 안에서 `last_posted_at`으로 확인하고 기록한다. AI 거부여도 소모, 삭제해도 반환 없음, 모더레이션 중 페이지 종료 시에만 되돌림. 관리자 면제 | D-12, D-28 |
| **INV-09** | 클라이언트 로딩은 구독 먼저 → REST 조회 → doodle ID 병합 순서를 지킨다 | D-09 |
| **INV-10** | 수치(캔버스, 칸, 청크, 상한, 쿨다운, 기준값, 타임아웃)는 `GraffitiProperties` 한 곳에만 둔다. 클라이언트는 서버가 내려준 값을 쓴다. 코드에 숫자를 직접 쓰지 않는다 | D-05~D-07 |
| **INV-11** | 종료되지 않은 페이지는 언제나 최대 1개다(DB 유니크 제약으로 강제) | D-15 |
| **INV-12** | 점유율 = 선분 단위 bbox(굵기 포함) 칸들의 참조 카운트 > 0 비율. 메모리 `cellCount`는 언제나 DB의 PUBLISHED doodle에서 다시 계산할 수 있어야 한다 | D-14, D-25 |
| **INV-13** | ML 모드가 `SHADOW`이면 ML 판정과 OCR 텍스트 검사 결과는 **게시 결과에 어떤 영향도 주지 않는다**(기록만). ML 서버 장애나 지연이 게시를 실패시키거나 `UNCHECKED`로 만들지 않는다 | D-34, D-40 |

## 6. 설정값 (`application.yml` → `GraffitiProperties`)

| 키 | 기본값 | 근거 |
|---|---|---|
| `graffiti.canvas.width` / `height` | 7680 / 4320 | D-05 |
| `graffiti.canvas.zoom-min` / `zoom-max` | 0.5 / 4.0 | D-05 |
| `graffiti.canvas.chunk-size` | 480 | D-06 |
| `graffiti.canvas.cell-size` | 48 | D-06 |
| `graffiti.page.countdown-threshold` | 0.70 | D-15 |
| `graffiti.page.countdown-duration` | 6h | D-15 |
| `graffiti.page.max-lifetime` | 7d | D-15 |
| `graffiti.doodle.max-bbox` | 1024 | D-11 |
| `graffiti.doodle.max-payload-bytes` | 2097152 | D-11 |
| `graffiti.doodle.palette` | 12색 hex 배열 | D-07 |
| `graffiti.doodle.stroke-widths` | [2,4,8,16] | D-07 |
| `graffiti.doodle.font-sizes` | [10,11,12,13,14,15,16,20,24,32,36,40,48,64,96] | D-07 |
| `graffiti.doodle.text-max-length` | 50 | D-07 |
| `graffiti.doodle.draft-idle-timeout` | 10m | D-11 |
| `graffiti.post-cooldown` | 30m | D-12 |
| `graffiti.realtime.max-messages-per-second` | 20 | D-12 |
| `graffiti.realtime.max-subscriptions` | 60 | D-30 |
| `graffiti.realtime.inbound-message-max-bytes` | 65536 | D-27 |
| `graffiti.query.max-chunks` / `max-batch-delete` | 60 / 50 | D-30 |
| `graffiti.moderation.timeout` | 5s | D-18 |
| `graffiti.moderation.ml.mode` | SHADOW | D-34, D-39 |
| `graffiti.moderation.ml.base-url` | http://ml:8000 (내부망) | D-36 |
| `graffiti.moderation.ml.timeout` | 3s | D-36 |
| `graffiti.moderation.ml.flag-threshold` | 모델 버전별 값 (평가로 결정) | D-37, D-39 |
| `graffiti.auth.allowed-domain` | khu.ac.kr | D-21 |
| `graffiti.auth.admin-emails` | 환경변수 `ADMIN_EMAILS` | D-23 |
| `graffiti.auth.session-timeout` | 30d (세션 쿠키 max-age와 Spring Session 만료에 같이 사용) | D-21 |
| `graffiti.auth.nickname-max-attempts` | 10 | D-22 |
| `graffiti.web.allowed-origins` | 환경변수 `APP_ORIGIN` | SEC-03 |

비밀값(`GOOGLE_CLIENT_ID/SECRET`, `OPENAI_API_KEY`, `KHUCHAT_API_KEY`, `DB_PASSWORD`)은 환경변수로만 받습니다(SEC-06).

## 7. 배포 구성

```
GitHub (main 머지) ─▶ GitHub Actions: test → build images → push (GHCR) → SSH로 EC2에서 docker compose pull && up -d
EC2 (메모리 4GB급, D-36) 보안 그룹: 80/443만 개방, 22는 팀원 IP만 (SEC-10)
docker-compose: nginx, app, ml(mem_limit, 외부 포트 없음), mysql(볼륨), certbot
ml 이미지 빌드 시 models.lock의 버전을 S3에서 받아 포함하거나, 컨테이너 시작 시 내려받음 (12-deploy에서 확정)
```

세부 절차는 `docs/specs/12-deploy.md`에서 확정합니다.
