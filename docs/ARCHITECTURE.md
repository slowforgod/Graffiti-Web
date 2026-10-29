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
                       │   │     ├─ ChatKhuProvider ───────────▶ ChatKHU API (텍스트)    │
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
│   │                        커맨드: ApplyFootprint, EndPage, GetPageSnapshot, GetMinimap, EnsureLivePage, InstallPage
│   ├── PageReader           요청 스레드용 읽기(큐를 거쳐 스냅샷·미니맵), PageController(/api/pages/current, /minimap)
│   ├── LivePage             STOMP 검증용 현재 페이지 캐시 (페이지 이벤트마다 무효화, 큐 왕복을 줄임)
│   ├── PageState            메모리 상태 (cellCount, status, scheduledEndAt)
│   ├── Footprint            선분 bbox(굵기 포함)/텍스트 박스 → 칸 집합 (순수 로직)
│   ├── OccupancyGrid        참조 카운트, 점유율, 비트맵 (순수 로직)
│   ├── PageLifecycle        상태 전이, 종료 시각 계산 (순수 로직)
│   ├── PageTimer            종료 예약 (PageStatusChanged마다 재예약)
│   ├── PageBootstrap        재시작 복구 (웹 서버 시작 전, PublishedFootprintSource로 점유율 재계산)
│   ├── PostStore            큐 안에서 쓰는 게시 저장 포트 (쿨다운 예약·되돌림, doodle INSERT, 보류·거부 저장과 게시,
│   │                        moderation_action 기록). doodle 패키지가 구현
│   └── event/               PageStatusChanged, OccupancyChanged, PageTurned, DoodlePublished, DoodleRemoved
│                            (커밋 후 발행 → STOMP로 중계)
├── doodle/        게시(DoodleController: 본문 2MB 상한·엄격한 JSON, DoodleValidator 순수 검증·bbox·칸·청크,
│                  PublishService: 예약 → 검사 → 게시 → 실패 시 되돌림, JdbcPostStore, PublishedDoodleFootprints),
│                  청크 조회(ChunkList 순수 검증, ChunkDoodleQuery JDBC, ChunkDoodleController),
│                  내 doodle(MyDoodleController: 목록·삭제·일괄 삭제, MyDoodleService: 행 읽기와 칸 계산은 큐 밖,
│                  RemoveDoodles 큐 명령으로 전부 아니면 전무, MyDoodleQuery)
├── moderation/    ModerationCheck 인터페이스(검사기 하나), ModerationChecks(설정 이름 → 구현, 없으면 StubCheck),
│                  ModerationService(판정), CheckRunner(순서대로 호출·타임아웃), VerdictCombiner(순수, 판정 합성),
│                  WordFilterCheck + WordList + TextNormalizer(resources/moderation/*.txt). ChatKhu, OpenAi는 아직 스텁.
│                  07b: DoodleRenderer(순수, PNG), MlModerationProvider(ML 서버 HTTP), MlAnalysis(ML 경로·SHADOW
│                  백그라운드), MlPredictionRecorder(ml_prediction)
├── realtime/      STOMP 설정(WebSocketConfig, 하트비트), RealtimeInterceptor(구독 목적지 검증, SEND 권한·속도 제한),
│                  SubscribeReceipts(구독 등록 후 RECEIPT, INV-09), StompErrorHandler(ERROR 프레임 = 에러 코드),
│                  Broadcaster + PageEventRelay(page/event → 메타 토픽, DoodlePublished → 청크 토픽), CursorController(/app/cursor 중계),
│                  DraftController + DraftRules(/app/draft 검증·중계, 연결 종료 시 취소), RealtimeErrorAdvice
├── admin/         관리자 API: 검토 목록(AdminDoodleQuery), 보류·거부 게시와 거부(AdminReviewService → 큐), 조치 기록 조회
└── common/        에러 응답 형식(ApiError), 예외 → 에러 코드 매핑, 시계(Clock) 주입
```

- **순수 로직**(`OccupancyGrid`, `PageLifecycle`, `DoodleValidator`, 쿨다운 판정, `ModerationVerdictCombiner`)은 Spring에 의존하지 않는 클래스로 만들어 단위 테스트합니다.

### ML 서버 구조 (`ml/`)

```
ml/
├── serving/       FastAPI 추론 서버 (POST /v1/moderate, GET /health), Python 3.12
│   ├── app/       main(엔드포인트), lock(models.lock 검증), decision(라벨, 순수), image_input(PNG 검증),
│   │              models(ImageClassifier·OcrEngine 프로토콜, 레지스트리), schemas, settings
│   ├── tests/     pytest (가짜 분류기로 API, 잘못된 입력, 체크섬, 로그에 OCR 글자 없음)
│   └── Dockerfile 비 root, 빌드 컨텍스트는 ml/
├── training/      미세조정 스크립트 (운영 데이터 추출본 사용, 작성자 정보 없음) — 07b 이후
├── prompts/       카테고리 문장 <version>.toml (models.lock이 이름과 sha256으로 잠금)
├── eval/          ocr_eval.py·ocr_metrics.py(OCR 평가), build_set.py(세트 생성), calibrate.py(임계값 보정), evaluate.py(서버 호출), metrics.py·calibration.py(순수). 평가 이미지는 sets/(git 제외)와 S3
└── models.lock    TOML. 모델 버전, arch, S3 경로, 파일 이름, sha256, 문장 파일과 sha256, crop_to_content, 카테고리별 임계값 (가중치 파일은 git에 없음)
```

- 서버는 시작할 때 `models.lock`을 읽고 `MODEL_DIR/<file>`(가중치), `ml/prompts/<prompts>`(카테고리 문장), OCR 파일의 sha256이 모두 맞을 때만 모델을 만듭니다. 모델이 없거나 체크섬이 다르면 뜨기는 하지만 `/health`와 `/v1/moderate`가 503입니다(INV-13: Spring은 판정 없음으로 처리).
- OCR은 EasyOCR(한국어+영어)입니다. 글자 덩어리 중 `[ocr] min_confidence` 이상만 남겨 `ocr.text`로 돌려줍니다(`ml/serving/app/ocr.py`). OCR 파일도 sha256이 맞아야 로드하며, 하나라도 실패하면 서버는 503입니다.
- 분류기는 OpenCLIP 제로샷(paired)입니다. 카테고리마다 `sigmoid(logit_scale × (cos(이미지, positive) − cos(이미지, negative)))`로 0~1 점수를 냅니다(`ml/serving/app/clip_classifier.py`).
- 라벨은 서버가 정합니다: 카테고리 중 하나라도 `models.lock`의 임계값 이상이면 `FLAGGED`.
- 현재 시각은 항상 주입받은 `java.time.Clock`으로 얻습니다. 타이머와 쿨다운 테스트에서 시간을 조작하기 위해서예요.

## 3. 프론트엔드 구조

```
frontend/src/
├── api/           REST 클라이언트(CSRF 헤더 자동 첨부), 에러 코드 타입
├── auth/          /api/me 상태, 로그인·로그아웃 바, 동의 화면, 로그인 실패 안내
├── realtime/      STOMP 연결(connection.ts, 구독마다 receipt), RealtimeLink 인터페이스, 메타 토픽 동기화(metaSync.ts:
│                  미니맵·점유율·page:turned), 커서 전송 간격 제한(throttle.ts)
├── canvas/        Konva Stage(CanvasView), 뷰포트(viewport.ts: 팬/줌, config의 min~max), 청크 계산(chunks.ts),
│                  보이는 청크 동기화(chunkSync.ts: 구독 → 조회 → 병합, INV-09) + doodle 저장소(doodleStore.ts),
│                  다른 사람 커서(cursors.ts, CursorLayer), 다른 사람 초안(remoteDrafts.ts) + 초안 레이어(DraftLayer), 배경 격자
├── tools/         내 초안 모델(draft.ts: 1px 점 버림, bbox 상한, 지우개 판정), 묶음 전송(draftSender.ts),
│                  텍스트 크기 측정(textMeasure.ts), 도구·초안 훅(useDraft.ts: 무동작·연결 끊김 취소),
│                  ✓ 게시 본문과 호출(publish.ts), 서버 시각 기준 쿨다운(cooldown.ts)
├── mine/          내 낙서(08): 목록·삭제 호출(myDoodles.ts), 선택·삭제 확인·PNG 저장 훅(useMyDoodles.ts),
│                  브라우저 안에서 그리는 PNG(png.ts: 내 doodle만, 흰 배경, canvas.toBlob, D-13)
├── ui/            툴바(이동/펜/텍스트/지우개, 색, 굵기, 글자 크기, 내 낙서), ✓/✗(DraftActions), 텍스트 입력 상자,
│                  내 낙서 패널(MyDoodlesPanel: 캔버스에서 고르기, 지우기 확인, PNG 저장),
│                  상단 상태바(StatusBar: 제N장, 점유율, 남은 시간), T−5분 카운트다운(TurnWarning), 남은 시간 계산
│                  (pageTime.ts), 미니맵, 토스트
├── admin/         관리자 화면
└── shared/        설정 상수(서버의 /api/pages/current 응답으로 받음), 타입
```

- 확정된 doodle은 배경 레이어와 분리된 doodle 레이어 하나에 **id 순서(게시 순서)**로 그립니다(겹칠 때 최신이 위). 보이는 청크의 doodle만 메모리에 둡니다. 초안과 커서는 별도의 동적 레이어에 둡니다. 청크별 비트맵 캐시는 성능 문제가 확인되면 도입합니다.
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
  │                               │──────────────── moderate(doodle) ──────────────────────────▶│ ④ 텍스트: 금칙어→ChatKHU→OpenAI
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
- ③ 예약(06): `ReservePost`가 DB에서 차단·역할·`last_posted_at`을 읽고, 쿨다운이 지났으면 `UPDATE users SET last_posted_at = now WHERE id = ? AND banned_at IS NULL AND last_posted_at <=> 읽은 값`. 관리자는 아무것도 쓰지 않음. 예약 시각 = `submitted_at`
- 되돌림(D-28): ④에서 예외가 나거나 ⑤가 `PAGE_ENDED`·DB 오류로 실패하면 `ReleasePost`가 `last_posted_at`을 예약 전 값으로(그사이 바뀌지 않았을 때만). AI 거부(07)는 되돌리지 않음
- ⑤ 게시(06): doodle과 `doodle_chunk`(bbox가 걸치는 청크) INSERT와 카운트다운 UPDATE가 한 트랜잭션. 커밋 뒤 `DoodlePublished` → 청크마다 `doodle:published`, 이어서 `minimap:diff`, `page:status`
- ④ 검사(07 틀, `specs/07-moderation-frame.md`): `ModerationService`가 설정한 검사기(`graffiti.moderation.text-checks`, `drawing-checks`)를 순서대로 부르고(각각 타임아웃, BLOCK이면 멈춤) `VerdictCombiner`가 합칩니다. BLOCK → `REJECTED`, REVIEW·ERROR → `HELD`, 모두 PASS → `PASSED`, 스텁(아직 없는 검사기)이 끼어 있으면 `UNCHECKED`이고 `graffiti.moderation.unchecked`(기본 `HOLD`)가 보류할지 게시할지 정합니다. 그림의 ML 분석은 `ml.mode`대로 돌고 SHADOW면 저장 뒤 백그라운드에서 기록만(INV-13, §4.7)
- **실제 검사기는 아직 없습니다**(금칙어는 목록 파일이 비어 있어 SKIPPED, ChatKHU·OpenAI는 스텁. ML 경로는 연결됐고 SHADOW라 판정에 들어가지 않음). 기본 설정이면 모든 낙서가 보류되므로, `unchecked: PUBLISH`로 바꿔 공개 배포하면 06과 같은 미검사 상태가 됩니다(PIPELINE)
- 거부·보류되면 `WithholdDoodle`이 `status=REJECTED|HELD`로 저장하고(`doodle_chunk` 포함, 점유율·브로드캐스트 없음) `moderation_action(AI, REJECT|HOLD)`를 같은 트랜잭션에서 기록합니다. 쿨다운은 그대로 소모됩니다(D-12). 그사이 페이지가 넘어갔으면 `PAGE_ENDED` + 되돌림
- 관리자가 보류·거부를 게시하면(`PublishWithheld`, 현재 페이지만) ⑤와 같이 점유율·`doodle:published`가 나가고 `moderation_action(ADMIN, APPROVE|RESTORE)`가 남습니다
- DB 쓰기는 큐 작업 안에서 트랜잭션 하나로 묶고, 커밋이 성공한 **뒤에** 메모리 상태 갱신과 브로드캐스트를 합니다. DB가 실패하면 메모리는 그대로입니다.

### 4.2 화면 이동과 초기 로딩 (INV-09)

```
뷰포트 변경 → 보이는 청크 집합 계산 (최대 60개)
   ├─ 새로 보이는 청크: ① STOMP SUBSCRIBE /topic/page.{p}.chunk.{i} (receipt 헤더 포함)
   │                       (도착하는 이벤트는 청크별 대기열에 쌓음, 커서는 바로 표시)
   │                    ② 모든 RECEIPT를 받은 뒤 GET /api/pages/{p}/doodles?chunks=i,j,...
   │                    ③ 응답 + 대기열 병합: doodle ID로 중복 제거,
   │                       대기열의 doodle:removed는 응답에서도 제거
   └─ 벗어난 청크:     UNSUBSCRIBE, 해당 청크에만 있던 doodle은 메모리에서 해제
```

- 메타 토픽도 같은 순서입니다: SUBSCRIBE(receipt) → GET /minimap → 대기열의 `minimap:diff` 재적용. `page:turned`나 `PAGE_ENDED`를 받으면 `/api/pages/current`부터 다시 불러옵니다.
- 연결이 없으면 REST로만 불러오고, (재)연결되면 보이는 청크와 메타 토픽을 모두 다시 구독 → 조회합니다. 끊긴 사이의 이벤트는 다시 조회한 응답이 대신합니다.

### 4.3 초안 중계 (저장 안 함, INV-06)

```
펜 다운 → draftId(UUID) 생성 (시작 메시지 없음, 첫 메시지가 초안을 시작)
그리는 중 draftIntervalMs(100ms)마다 → SEND /app/draft {op:"points", strokeIndex, c, w, points}  (그동안 커서 전송 멈춤)
지우개 → {op:"erase", strokeIndex} / 텍스트 → {op:"text", bbox} (글자는 보내지 않음, D-33)
서버: 인터셉터(작성자, 초당 20회, 64KB) → DraftController: 현재 페이지, DraftRules 검증, 초안당 2MB
      → 묶음 bbox가 닿는 청크 토픽으로 draft:update 중계 (큐를 거치지 않음)
      연결 상태엔 draftId·종류·닿은 청크·누적 바이트만. 한 연결의 프레임은 순서가 바뀔 수 있어 synchronized,
      방금 취소한 draftId의 늦은 프레임은 버림
✗ / 10분 무동작(작성자 화면) → {op:"cancel"} → 닿았던 모든 청크에 draft:cancel
연결 끊김 → 서버가 세션 종료 이벤트에서 draft:cancel, 작성자 화면도 초안 폐기
보는 쪽: 10분 동안 소식 없는 초안은 스스로 지움 (서버 타이머 없음)
✓ → REST 게시(4.1). 성공하면 클라이언트가 cancel을 보내 반투명 초안을 지움
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
           → 새 페이지에 "페이지가 넘어가 그리던 초안이 사라졌어요."(초안이 있었을 때) 또는 "새 페이지로 넘어왔어요."
           (page:turned를 놓쳐도 종료 시각 + 5초에 /api/pages/current를 다시 불러 같은 결과)
```

- 상태 전이는 `UPDATE ... WHERE id=? AND status=?`처럼 **이전 상태를 조건으로** 실행합니다. 영향받은 행이 0이면 예외를 던집니다.
- T−5분 카운트다운은 클라이언트가 `scheduledEndAt`과 서버 시각 차이(`serverTime`)로 계산합니다. 서버 푸시는 필요 없습니다. 상태바(`ui/StatusBar.tsx`)와 카운트다운(`ui/TurnWarning.tsx`)은 `page:status`로 상태와 종료 시각을, `minimap:diff`로 점유율을 갱신하고, 시계는 경고 1분 전부터 1초마다, 그 전에는 30초마다 다시 봅니다(`ui/pageTime.ts`, 09).

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

- 내 doodle 삭제(08, `RemoveDoodles`): 행 읽기, 작성자·페이지·상태 확인, 칸 계산(`StoredFootprint`)은 큐 밖에서 하고, 큐 안에서는 한 트랜잭션으로 doodle마다 `UPDATE ... WHERE author_id = 나 AND status = 'PUBLISHED'`를 실행합니다. 하나라도 0행이면 `STATE_CONFLICT`로 전부 롤백하고 메모리도 그대로 둡니다. 커밋 뒤 메모리 칸을 바꾸고 doodle마다 `DoodleRemoved`(→ 저장된 `doodle_chunk`마다 `doodle:removed`), 비워진 칸이 있으면 `OccupancyChanged`(→ `minimap:diff`). 점유율이 내려가도 카운트다운은 멈추지 않습니다(INV-03). 끝난 페이지의 doodle은 지울 수 없습니다(`PAGE_ENDED`).

### 4.7 그림 검사와 자체 ML 모델 (D-34 ~ D-40)

```
그림 doodle ─▶ ④ ModerationService: drawing-checks (openai-image, 07)
               │   mode=ENFORCE면 여기서 MlAnalysis.analyze 결과도 판정에 넣음
               ▼
             ⑤ 저장 (큐) → doodle.id → 201 응답
               │
               └─▶ MlAnalysis.afterStored (큐 밖)
                     mode=SHADOW  → 백그라운드(가상 스레드, 동시 max-in-flight개, 넘치면 건너뜀)에서 analyze
                     mode=ENFORCE → ④에서 쓴 결과를 기록
                     mode=OFF     → 아무것도 안 함

MlAnalysis.analyze(payload)
  DoodleRenderer → PNG (흰 배경, bbox, 최대 render-max-side, 캔버스처럼 둥근 선)
  MlModerationProvider → ML 서버 POST /v1/moderate (제한 3초, 프록시 없이)
    ├─ 시간 초과 / 4xx·5xx / 스키마 위반 → TIMEOUT 또는 ERROR (판정 없음)
    └─ OK → label, scores, ocr.text, ocr.confidence
  ocr.text가 있으면 텍스트 검사 경로(text-checks: 금칙어 → ChatKHU → OpenAI, CheckRunner 공유)
    → ocr_text_outcome = PASS | REVIEW | BLOCK | ERROR | SKIPPED (없으면 NONE)
  MlPredictionRecorder → ml_prediction INSERT (게시 트랜잭션과 분리, 실패해도 게시 결과 불변)

ENFORCE에서 ML 결과를 하나의 검사(ml)로 합성 (07b 결정)
  이미지 FLAGGED                         → BLOCK (점수가 가장 높은 카테고리)
  OCR 글자 BLOCK, 카테고리 2~4             → BLOCK
  OCR 글자 BLOCK(PROFANITY) 또는 REVIEW    → REVIEW (HELD)
  OCR 글자 검사가 스텁뿐                   → SKIPPED
  ML 서버·렌더링·OCR 글자 검사 장애        → 판정에서 빠짐 (HELD 아님, INV-13)
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
| **INV-05** | doodle은 모더레이션 결과가 `PASSED` 또는 `UNCHECKED`일 때만 게시된다. 검사를 건너뛰는 경로는 없다. `HELD`·`REJECTED`는 관리자가 게시할 때만 캔버스에 나온다(`moderation_action` 기록) | D-18, D-23 |
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
| `graffiti.page.countdown-warning` | 5m (클라이언트가 T−5분 경고에 사용, `countdownWarningSec`) | D-16 |
| `graffiti.doodle.max-bbox` | 1024 | D-11 |
| `graffiti.doodle.max-payload-bytes` | 2097152 | D-11 |
| `graffiti.doodle.palette` | 12색 hex 배열 | D-07 |
| `graffiti.doodle.stroke-widths` | [2,4,8,16] | D-07 |
| `graffiti.doodle.font-sizes` | [10,11,12,13,14,15,16,20,24,32,36,40,48,64,96] | D-07 |
| `graffiti.doodle.default-font-size` | 24 (font-sizes 안의 값이어야 함) | D-07 |
| `graffiti.doodle.text-max-length` | 50 | D-07 |
| `graffiti.doodle.draft-idle-timeout` | 10m | D-11 |
| `graffiti.post-cooldown` | 30m | D-12 |
| `graffiti.realtime.max-messages-per-second` | 20 | D-12 |
| `graffiti.realtime.max-subscriptions` | 60 | D-30 |
| `graffiti.realtime.inbound-message-max-bytes` | 65536 (STOMP 프레임 한도와 WebSocket 컨테이너 버퍼에 같이 사용) | D-27 |
| `graffiti.realtime.cursor-interval` | 100ms (클라이언트 커서 전송 간격, `cursorIntervalMs`) | 04 |
| `graffiti.realtime.draft-interval` | 100ms (클라이언트 초안 전송 간격, `draftIntervalMs`. 초당 20회 한도 안) | 05, D-12 |
| `graffiti.realtime.heartbeat` | 10s (STOMP 양방향 하트비트, `heartbeatMs`) | 04 |
| `graffiti.query.max-chunks` / `max-batch-delete` | 60 / 50 | D-30 |
| `graffiti.query.admin-page-size-max` | 100 (관리자 목록 한 번의 최대 개수) | 07 틀 |
| `graffiti.web.admin-requests-per-second` | 20 (관리자 한 명의 `/api/admin/**` 요청, 초당) | SEC-08/09, 11 |
| `graffiti.web.write-requests-per-second` | 10 (로그인 사용자 한 명의 그 밖의 `/api/**` POST·PUT·PATCH·DELETE, 초당) | SEC-08/09, 11 |
| `graffiti.moderation.timeout` | 5s (검사기 하나마다) | D-18 |
| `graffiti.moderation.unchecked` | 환경변수 `MODERATION_UNCHECKED`, 기본 `HOLD`. 실제 검사 없이 스텁만 지난 낙서를 보류(`HOLD`)할지 미검사로 게시(`PUBLISH`, 로컬 전용)할지 | 07 틀, D-42 |
| `graffiti.moderation.text-checks` | [word-filter, chatkhu, openai-text] (순서대로, 없는 구현은 스텁) | D-19 |
| `graffiti.moderation.drawing-checks` | [openai-image] | D-19 |
| `graffiti.moderation.ml.mode` | SHADOW | D-34, D-39 |
| `graffiti.moderation.ml.base-url` | http://ml:8000 (내부망) | D-36 |
| `graffiti.moderation.ml.timeout` | 3s | D-36 |
| `graffiti.moderation.ml.max-in-flight` | 4 (SHADOW 백그라운드 분석 동시 상한, 넘치면 건너뜀) | 07b 결정 6 |
| `graffiti.moderation.ml.render-max-side` | 1024 (ML 서버로 보내는 PNG의 긴 변) | 07b, API.md §7 |
| `graffiti.auth.allowed-domain` | khu.ac.kr | D-21 |
| `graffiti.auth.admin-emails` | 환경변수 `ADMIN_EMAILS` | D-23 |
| `graffiti.auth.session-timeout` | 30d (세션 쿠키 max-age와 Spring Session 만료에 같이 사용) | D-21 |
| `graffiti.auth.nickname-max-attempts` | 10 | D-22 |
| `graffiti.web.allowed-origins` | 환경변수 `APP_ORIGIN` | SEC-03 |

비밀값(`GOOGLE_CLIENT_ID/SECRET`, `OPENAI_API_KEY`, `CHATKHU_API_KEY`, `DB_PASSWORD`)은 환경변수로만 받습니다(SEC-06).

## 7. 배포 구성

```
GitHub main 머지 ─▶ CI 통과 ─▶ deploy.yml: bootJar, npm build ─▶ 이미지 2개를 GHCR에 push (태그 = 커밋 SHA)
                                         └─▶ (저장소 변수 STAGING_HOST가 있을 때) SSH: compose pull && up -d ─▶ https 헬스체크

EC2 (메모리 4GB급, D-36) 보안 그룹: 80/443, 22는 팀원과 배포용 (SEC-10)
/opt/graffiti: compose.yml(deploy/compose.yml 복사본), .env(사람이 작성, SEC-06), image.env(배포가 작성)

  web (nginx, :80 :443) ─┬─ /                 dist (SPA, index.html 폴백, assets 1년 캐시)
                         ├─ /api /oauth2 /login ─▶ app:8080   limit_req, X-Forwarded-Proto/For
                         ├─ /ws               ─▶ app:8080   Upgrade, IP당 연결 수 제한
                         ├─ /actuator/health  ─▶ app:8080   (그 밖의 /actuator는 404)
                         └─ /.well-known/acme-challenge ─▶ certbot webroot
  app (Spring, 외부 포트 없음, mem 1g) ── mysql 8.4 (외부 포트 없음, 볼륨, mem 768m)
  certbot (12시간마다 renew), web은 6시간마다 reload
  ml (07b에서 추가: 내부망 전용, mem_limit, D-36)
```

- 이미지는 CI가 만든 산출물만 담습니다(`.dockerignore` 허용 목록). 소스, `node_modules`, `.env`는 들어가지 않습니다.
- nginx는 Docker DNS로 `app`을 매 요청 다시 찾으므로 배포로 app 컨테이너가 바뀌어도 nginx를 다시 띄울 필요가 없습니다.
- 앱은 `server.forward-headers-strategy: native`로 내부 주소(nginx)가 보낸 `X-Forwarded-Proto/For`만 믿습니다. 그래서 OAuth 리디렉션 URI가 `https://<도메인>/login/oauth2/code/google`이 됩니다.
- TLS: 인증서가 없으면 nginx가 HTTP 전용 설정으로 떠서 Let's Encrypt 챌린지에 응답하고, 인증서가 생긴 뒤 재시작하면 HTTPS 설정(80 → 443 리다이렉트, HSTS)으로 바뀝니다.
- nginx가 직접 내는 429, 413, 502도 API 에러 형식(`RATE_LIMITED`, `PAYLOAD_TOO_LARGE`, `INTERNAL`)입니다. 접근 로그에는 쿼리 문자열을 남기지 않습니다(SEC-13, OAuth `code`).

| nginx 설정 (`deploy/nginx/`) | 값 | 근거 |
|---|---|---|
| `/api/` IP당 속도 | 20r/s, burst 40 | SEC-09 |
| `/oauth2/`, `/login/` IP당 속도 | 60r/m, burst 60 (학교 NAT 뒤 한 강의실이 동시에 로그인해도 통과) | SEC-09 |
| `/ws` IP당 동시 연결 | 100 (학교 NAT 뒤 여러 학생이 한 IP를 공유) | SEC-08 |

- nginx는 IP 단위로 막고, 앱은 그 뒤에서 **사용자 단위**로 한 번 더 막습니다(`auth/ApiGuards`, 11): 관리자 API는 경로 자체가 관리자 전용(핸들러가 `@CurrentUser(ADMIN)`을 빠뜨려도 403)이고 관리자마다 초당 20회, 그 밖의 상태 변경 요청은 사용자마다 초당 10회. 넘으면 `429 RATE_LIMITED`.
| `client_max_body_size` | 3m (앱의 2MB 한도보다 크게 두어 앱이 413을 API 형식으로 응답) | D-30 |

준비 절차(EC2, DNS, 서버 `.env`, GitHub 변수와 Secrets, 첫 인증서)는 `deploy/README.md`에 있습니다. 운영 공개, 백업과 복구 리허설(SEC-11)은 12b에서 정합니다.
