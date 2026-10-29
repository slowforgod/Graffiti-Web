# API 명세 (REST + STOMP)

> 엔드포인트나 메시지 형식을 바꾸면 이 문서를 **같은 커밋에서** 수정합니다.
> 모든 경로는 같은 도메인 기준입니다(D-02). 시각은 ISO-8601 UTC 문자열, ID는 문자열로 직렬화된 정수입니다(JS 정밀도 문제 방지).

## 0. 공통 규칙

### 인증과 CSRF
- 로그인은 세션 쿠키(`SESSION`, `HttpOnly`, `Secure`, `SameSite=Lax`)로 유지됩니다.
- 상태를 바꾸는 REST 요청(POST/PUT/DELETE)은 CSRF 토큰이 필요합니다. 서버가 `XSRF-TOKEN` 쿠키를 내려주면, 클라이언트는 같은 값을 `X-XSRF-TOKEN` 헤더로 보냅니다(SEC-03).
- 권한 표기: `공개`(비로그인 가능) / `로그인`(로그인 + 개인정보 동의 완료) / `작성자`(로그인 + 차단 아님) / `관리자`

### 에러 응답 형식
```json
{ "error": { "code": "COOLDOWN", "message": "다음 게시까지 12분 남았어요.", "details": { "retryAfterSec": 720 } } }
```

| code | HTTP | 의미 |
|---|---|---|
| `UNAUTHENTICATED` | 401 | 로그인 필요 |
| `CONSENT_REQUIRED` | 403 | 개인정보 동의 필요 |
| `FORBIDDEN` | 403 | 권한 없음 (남의 doodle 등) |
| `BANNED` | 403 | 차단된 사용자 |
| `CSRF_INVALID` | 403 | CSRF 토큰 불일치 |
| `DOMAIN_NOT_ALLOWED` | 403 | 학교 계정이 아님 (로그인 리다이렉트 결과로 전달) |
| `NOT_FOUND` | 404 | 대상 없음 |
| `PAGE_ENDED` | 409 | 현재 페이지가 아님 (INV-04) |
| `STATE_CONFLICT` | 409 | 대상의 상태가 이미 바뀜 (관리자가 이미 처리한 보류 doodle 등) |
| `VALIDATION_FAILED` | 400 | 입력 검증 실패. `details.field`, `details.reason` |
| `PAYLOAD_TOO_LARGE` | 413 | 2MB 초과 |
| `MODERATION_REJECTED` | 422 | 검사 거부. `details.category`(있을 때), `details.cooldownEndsAt`(관리자는 없음) |
| `COOLDOWN` | 429 | 게시 쿨다운. `details.retryAfterSec` |
| `RATE_LIMITED` | 429 | 요청 속도 초과 (nginx의 IP당 한도, 앱의 사용자당 한도: 관리자 API 초당 20회, 그 밖의 변경 요청 초당 10회, 11) |
| `INTERNAL` | 500 | 서버 오류 (내부 정보 노출 금지) |

---

## 1. 인증과 사용자

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/oauth2/authorization/google` | 공개 | Google 로그인 시작 (Spring Security 기본) |
| GET | `/login/oauth2/code/google` | 공개 | OAuth 콜백. 도메인 검증(SEC-04) 후 `/`로 리다이렉트. 학교 계정이 아니면 `/?loginError=DOMAIN_NOT_ALLOWED`, 그 밖의 실패(사용자 취소, Google 오류)는 `/?loginError=LOGIN_FAILED` |
| POST | `/api/auth/logout` | 로그인(동의 전 허용) | 세션 무효화. 204 |
| GET | `/api/me` | 공개 | 비로그인이면 `{ "authenticated": false }` |
| POST | `/api/me/consent` | 로그인(동의 전 허용) | 개인정보 동의 기록. 응답은 `GET /api/me`와 같은 형식. 다시 호출해도 최초 시각 유지 |

- 모든 `/api/**`는 비로그인이면 리다이렉트가 아니라 401 `UNAUTHENTICATED`입니다(`GET /api/me` 제외).
- `XSRF-TOKEN` 쿠키는 어떤 응답에서든 내려옵니다. 앱은 시작할 때 `GET /api/me`를 부르므로 첫 변경 요청 전에 쿠키가 있습니다.

`GET /api/me` (로그인 상태)
```json
{
  "authenticated": true,
  "id": "42",
  "nickname": "익명의 펭귄 #3842",
  "cursorColor": "#4F8EF7",
  "role": "USER",
  "consentRequired": false,
  "banned": false,
  "cooldownEndsAt": "2026-10-21T05:30:00Z"   // last_posted_at + post-cooldown, 지났으면 null (관리자는 늘 null)
}
```

## 2. 페이지

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/pages/current` | 공개 | 현재 페이지 정보와 클라이언트용 설정값 |
| GET | `/api/pages/{pageId}/minimap` | 공개 | 점유율 비트맵 (현재 페이지만) |
| GET | `/api/pages/{pageId}/doodles?chunks=0,1,17` | 공개 | 청크별 게시된 doodle (현재 페이지만, 최대 60청크) |

`GET /api/pages/current`
```json
{
  "id": "12", "seq": 12, "status": "COUNTDOWN",
  "occupancyPermille": 712,
  "createdAt": "2026-10-17T03:00:00.000Z",
  "countdownStartedAt": "2026-10-21T01:00:00.000Z",
  "scheduledEndAt": "2026-10-21T07:00:00.000Z",
  "serverTime": "2026-10-21T04:12:33.120Z",
  "config": {
    "width": 7680, "height": 4320, "chunkSize": 480, "cellSize": 48,
    "zoomMin": 0.5, "zoomMax": 4.0,
    "palette": ["#000000", "#FFFFFF", "..."], "strokeWidths": [2,4,8,16],
    "fontSizes": [10,11,12,13,14,15,16,20,24,32,36,40,48,64,96], "defaultFontSize": 24,
    "textMaxLength": 50, "maxBbox": 1024, "maxChunks": 60,
    "postCooldownSec": 1800, "countdownWarningSec": 300, "draftIdleTimeoutSec": 600,
    "cursorIntervalMs": 100, "draftIntervalMs": 100, "heartbeatMs": 10000,
    "countdownThresholdPermille": 700, "countdownDurationSec": 21600
  }
}
```
- `width`, `height`, `chunkSize`, `cellSize`, `countdownThresholdPermille`, `countdownDurationSec`는 **현재 페이지 행의 스냅샷 값**입니다(D-06, 09에서 넘김 규칙 추가: 상태바 안내용). 나머지는 서버 설정값입니다(INV-10).
- 상단 상태바는 `createdAt`을 시작 날짜(`YY/MM/DD부터`)로 늘 보여 주고, `COUNTDOWN`일 때만 `serverTime`으로 잰 시계 차이를 반영해 `scheduledEndAt`까지 남은 시간(`N시간 N분 남음`)을 보여 줍니다. 남은 시간이 `countdownWarningSec` 이하면 큰 카운트다운과 초안 경고를 띄웁니다(D-16, 09). 종료 시각이 지나고 5초 안에 `page:turned`가 오지 않으면 `/api/pages/current`를 다시 부릅니다.
- 세 엔드포인트(`/current`, `/minimap`, `/doodles`) 모두 비로그인으로 호출할 수 있습니다.

`GET /api/pages/{pageId}/minimap`
```json
{ "cols": 160, "rows": 90, "bitmap": "<base64, 1800바이트, 행 우선, 칸 하나당 1비트>", "occupancyPermille": 712 }
```
- 비트 순서: 칸 인덱스 `row * 160 + col`의 비트는 바이트 `index / 8`의 최상위 비트부터(`0x80 >>> (index % 8)`). `page.final_occupancy_bitmap`도 같은 형식입니다.

`GET /api/pages/{pageId}/doodles?chunks=...`
```json
{
  "pageId": "12",
  "doodles": [
    { "id": "9001", "kind": "DRAWING", "bbox": { "x": 1190, "y": 330, "w": 40, "h": 30 },
      "payload": { "v": 1, "strokes": [ { "c": 3, "w": 8, "p": [1200,340,1203,342] } ] } }
  ]
}
```
- 작성자 정보는 **절대 포함하지 않습니다**(D-22).
- 종료된 페이지 ID(또는 없는 ID)로 요청하면 `PAGE_ENDED`를 반환합니다(아카이브 미제공, D-17). `/minimap`도 같습니다.
- `chunks` 검증(SEC-02, SEC-09): 쉼표로 구분한 10진 정수, 각각 `0 ≤ i < 청크 수`(기본 144), 최대 `maxChunks`개(중복 포함 개수). 어기면 400 `VALIDATION_FAILED`, `details.field = "chunks"`, `details.reason`은 `required` / `too_many` / `not_a_number` / `out_of_range`. 중복은 한 번만 조회합니다.
- 청크 번호 = `cy * 16 + cx`. doodle은 bbox(`[x, x+w) × [y, y+h)`)가 걸친 청크마다 한 번 조회되지만 응답에는 한 번만, id 오름차순으로 들어갑니다.

## 3. doodle

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/pages/{pageId}/doodles` | 작성자 | 게시(✓). 검증 → 쿨다운 → 모더레이션 → 게시 (ARCHITECTURE 4.1) |
| GET | `/api/me/doodles?pageId=` | 로그인 | 내 doodle ID와 bbox 목록 (선택 삭제, PNG 저장용, 08) |
| DELETE | `/api/doodles/{doodleId}` | 작성자 | 내 doodle 삭제 (08) |
| POST | `/api/doodles/delete` | 작성자 | 내 doodle 일괄 삭제 `{ "ids": ["1","2"] }` (최대 50개, 하나라도 남의 것이면 전체 거절, 08) |

`POST /api/pages/{pageId}/doodles` 요청
```json
{ "kind": "DRAWING", "payload": { "v": 1, "strokes": [ ... ] } }
{ "kind": "TEXT", "payload": { "v": 1, "text": "안녕", "c": 0, "size": 24, "x": 1500, "y": 900 }, "bbox": { "w": 52, "h": 34 } }
```
응답
- `201` `{ "doodle": { "id", "kind", "bbox", "payload" }, "cooldownEndsAt": "..." }`. `bbox`는 서버 값(그림은 캔버스로 자른 값), `payload`는 검증한 필드만 다시 만든 것. 관리자는 `cooldownEndsAt: null`
- `202` `{ "held": true, "cooldownEndsAt": "..." }`: 검사가 애매하거나 실패해서(또는 실제 검사가 없어서) 관리자 검토를 기다림. 캔버스에 나오지 않고 쿨다운은 소모됨(07 틀, D-42 PROPOSED). doodle 정보는 돌려주지 않음
- `422 MODERATION_REJECTED` (쿨다운 소모됨, `details.category`, `details.cooldownEndsAt`). 이유 문장은 보내지 않음
- `429 COOLDOWN` (`message` "다음 게시까지 N분 남았어요.", `details.retryAfterSec`) / `409 PAGE_ENDED` / `400 VALIDATION_FAILED` / `413 PAYLOAD_TOO_LARGE` / `403 BANNED`
- 검증 실패, 크기 초과, 끝난 페이지는 쿨다운을 쓰지 않습니다. 검사 중 페이지가 넘어가거나 서버 오류가 나면 쿨다운을 되돌립니다(D-28)

내 doodle (08, D-13)
- `GET /api/me/doodles?pageId=12` → `200 { "pageId": "12", "doodles": [{ "id": "9001", "bbox": { "x", "y", "w", "h" } }] }`. 그 페이지에 **게시된(PUBLISHED)** 내 doodle만, id 오름차순. payload는 주지 않음(필요하면 bbox가 걸친 청크를 `GET /api/pages/{p}/doodles`로 읽음). 보류·거부된 것은 없음. 현재 페이지가 아니면 `409 PAGE_ENDED`, `pageId`가 없거나 양의 10진 정수가 아니면 `400 VALIDATION_FAILED`(`field: "pageId"`)
- `DELETE /api/doodles/{id}` → `204`. 큐 안에서(INV-01) `PUBLISHED → DELETED`(`removed_at`, `removed_by` = 작성자, 행은 남음, INV-07), 칸 −1, bbox가 걸친 청크마다 `doodle:removed`, 비워진 칸은 `minimap:diff`. 쿨다운은 돌려주지 않음(INV-08)
- `POST /api/doodles/delete` `{ "ids": ["1", "2"] }` → `204`. 전부 아니면 전무: 한 트랜잭션에서 하나라도 못 지우면 아무것도 지우지 않음. `ids`는 문자열 10진 정수 1~`max-batch-delete`(50)개(중복은 한 번만, 개수는 보낸 그대로 셈). 알 수 없는 필드, 숫자 id, 형식 오류는 `400`(`details.field` = `ids`, 필드 이름 또는 `body`, `reason` = `required`/`too_many`/`not_a_number`/`unknown`/`malformed`)
- 거절(삭제 둘 다, 앞의 것부터 확인): 없는 id `404 NOT_FOUND` → 남의 doodle `403 FORBIDDEN` → 끝난 페이지의 doodle `409 PAGE_ENDED` → 게시 상태가 아님(이미 지움, 보류·거부) `409 STATE_CONFLICT`. CSRF 없음 `403 CSRF_INVALID`, 차단됨 `403 BANNED`
- "내 낙서 PNG로 저장"은 서버 API 없이 브라우저에서 만듭니다(D-13)

검증 규칙(`DoodleValidator`, SEC-02)
- `v`는 1. 그림: 획 1개 이상, 각 획 점 1개 이상(짝수 개 정수), 좌표는 캔버스 범위 안, `c`는 팔레트 인덱스, `w`는 허용 굵기, 서버가 계산한 bbox(점 최소·최대를 굵기 절반만큼 넓혀 `floor`/`ceil`) ≤ 1024². 그림에 `bbox`를 보내면 거절
- 텍스트: 1~50자(줄바꿈 제외, 코드 포인트 기준), 공백만은 거절, 줄바꿈 외 제어 문자 금지, `size`는 허용 목록 값, `(x, y)`와 상자 전체가 캔버스 안, bbox 상한(D-31: 너비 ≤ `size` × 가장 긴 줄 글자 수, 높이 ≤ `size` × 1.4 × 줄 수, 각각 1~1024) + `size` × 줄 수 ≤ 1024
- 요청 본문 2MB 이하(넘으면 읽기를 멈추고 413), 알 수 없는 필드·중복 키·문자열 숫자·소수 좌표는 거절
- 실패하면 `details.field`(`strokes`, `p`, `c`, `w`, `text`, `size`, `x`, `bbox`, `kind`, `v`, 알 수 없는 필드 이름, 형식 오류는 `body`)와 `details.reason`

## 4. 관리자 (`/api/admin/**`, 관리자 전용)

- 경로 단위로 관리자만 통과합니다(역할은 요청마다 DB에서 읽음). 관리자마다 초당 `graffiti.web.admin-requests-per-second`(20)회까지, 넘으면 `429 RATE_LIMITED` (11)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/doodles?moderation=HELD\|REJECTED\|UNCHECKED&page=0&size=20` | 보류·거부·미검사 게시 내역 (작성자 이메일, 닉네임, 렌더링용 payload 포함). **07 틀에서 구현** |
| GET | `/api/admin/doodles/{id}` | doodle 상세 (작성자 이메일 포함) |
| POST | `/api/admin/doodles/{id}/restore` | HELD 또는 REJECTED → PUBLISHED (현재 페이지만). **07 틀에서 구현**. UNCHECKED 표시 해제는 아직 없음 |
| POST | `/api/admin/doodles/{id}/reject` | HELD → REJECTED (끝난 페이지도 가능). **07 틀에서 구현** |
| DELETE | `/api/admin/doodles/{id}` | 아무 doodle 삭제 (종료된 페이지 포함) |
| POST | `/api/admin/doodles/delete` | 일괄 삭제 `{ "ids": [...] }` |
| GET | `/api/admin/users?sort=rejects&page=0&size=20` | AI 적발 횟수 순 사용자 |
| POST | `/api/admin/users/{id}/ban` | `{ "reason": "...", "hideCurrentPageDoodles": true }` |
| POST | `/api/admin/users/{id}/unban` | 차단 해제 |
| GET | `/api/admin/actions?page=0&size=50` | 조치 기록, 최신순 (SEC-14). **07 틀에서 구현** |

07 틀에서 구현한 관리자 API (나머지는 10에서)
- 목록: `moderation` 기본 `HELD`. HELD는 오래된 순(검토 대기열), REJECTED·UNCHECKED는 최신순. `size`는 1~`graffiti.query.admin-page-size-max`(100), `page` ≥ 0, 아니면 400 `VALIDATION_FAILED`
  - 응답 `{ "items": [{ "id", "pageId", "pageEnded", "kind", "bbox", "payload", "status", "moderationState", "moderationDetail", "submittedAt", "author": { "id", "nickname", "email" } }], "page", "size", "total" }`. `moderationDetail` 형식은 DB.md §3
- `restore`: `200 { "doodle": { "id", "kind", "bbox", "payload" } }`. 게시와 같이 큐 안에서 점유율 반영, 청크마다 `doodle:published`, `moderation_action(ADMIN, APPROVE|RESTORE)`. 끝난 페이지면 `409 PAGE_ENDED`(보류 그대로), 이미 처리됐으면 `409 STATE_CONFLICT`, 없으면 `404`
- `reject`: `200 { "id", "status": "REJECTED" }`, `moderation_action(ADMIN, REJECT)`. HELD가 아니면 `409 STATE_CONFLICT`
- `actions`: `{ "items": [{ "id", "actorType", "actorId", "action", "targetDoodleId", "targetUserId", "reason", "detail", "createdAt" }], "page", "size", "total" }`
- 관리자 화면은 `/admin`(nginx가 `index.html`로 응답). 미리보기는 캔버스(Konva)로만 그림(SEC-12)

관리자는 메인 캔버스에서도 아무 doodle이나 선택해 `DELETE /api/admin/doodles/{id}`를 호출할 수 있습니다.

## 5. 운영

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/actuator/health` | 공개 | 헬스체크 (상세 정보 비노출). 다른 actuator 엔드포인트는 비활성화 |

---

## 6. STOMP

### 연결
- 엔드포인트: `wss://<도메인>/ws` (네이티브 WebSocket, SockJS 없음)
- 핸드셰이크 때 세션 쿠키로 사용자를 식별합니다. 비로그인도 연결과 구독은 가능하고, 전송(SEND)은 `작성자` 권한이 필요합니다.
- `Origin` 헤더가 `APP_ORIGIN`과 다르면 연결을 거절합니다(SEC-03).
- 인바운드 메시지 최대 64KB(D-27), 세션당 구독 최대 60개(D-30)
- 하트비트는 양방향 `heartbeatMs`(페이지 설정, 기본 10초)입니다. 클라이언트는 `/api/pages/current`의 `config.heartbeatMs`를 씁니다.

### 구독 (SUBSCRIBE)

| 목적지 | 권한 | 내용 |
|---|---|---|
| `/topic/page.{pageId}.chunk.{index}` | 공개 | 해당 청크의 doodle, 초안, 커서 이벤트. 현재 페이지와 0~143 범위만 허용 |
| `/topic/page.{pageId}.meta` | 공개 | 점유율, 상태, 미니맵 변경, 페이지 넘김 |
| `/user/queue/notice` | 로그인 | 개인 알림 (차단됨 등) |
| `/user/queue/errors` | 로그인 | 거절된 SEND의 에러 (연결은 유지) |

- SUBSCRIBE에 `receipt` 헤더를 넣으면, 서버가 구독을 등록한 **뒤에** `RECEIPT`를 보냅니다. 클라이언트는 영수증을 받은 뒤 REST로 조회해야 그 사이 이벤트를 놓치지 않습니다(INV-09: 구독 → 조회 → 병합).
- 허용되지 않은 목적지(지난 페이지, 범위 밖 청크, 비로그인의 `/user/**`)는 `ERROR` 프레임으로 거절하고 연결을 닫습니다. 61번째 구독은 `RATE_LIMITED`입니다.

### 전송 (SEND) — 모두 `작성자` 권한, 초당 20회 제한, 현재 페이지만(INV-04)

- 목적지는 `/app/**`만 허용합니다. `/topic/**` 등으로 직접 보내면 `FORBIDDEN`으로 거절합니다.
- 권한(`UNAUTHENTICATED`, `CONSENT_REQUIRED`, `BANNED`)과 초당 횟수(`RATE_LIMITED`)는 `ERROR` 프레임으로 거절하고 연결을 닫습니다.
- 내용이 잘못된 메시지(`VALIDATION_FAILED`, 지난 페이지의 `PAGE_ENDED`)는 연결을 유지하고 `/user/queue/errors`로 `{ "type": "error", "code": "..." }`를 보냅니다.

`/app/cursor`
```json
{ "pageId": "12", "x": 1200, "y": 800 }
```
- 클라이언트는 `config.cursorIntervalMs`(기본 100ms)마다 최대 1번 보냅니다. 서버는 좌표가 속한 청크 토픽으로 중계하고, 다른 청크로 옮기거나 연결이 끊기면 이전 청크에 `cursor:leave`를 보냅니다. 커서는 저장하지 않습니다(INV-06).

`/app/draft` (05-draft)
```json
{ "pageId": "12", "draftId": "uuid", "op": "points", "strokeIndex": 0, "c": 3, "w": 8, "points": [1200,340,1203,342] }
{ "pageId": "12", "draftId": "uuid", "op": "erase",  "strokeIndex": 2 }      // 초안 편집 중 지우개, 획을 통째로
{ "pageId": "12", "draftId": "uuid", "op": "text",   "bbox": { "x": 1500, "y": 900, "w": 52, "h": 34 } }
{ "pageId": "12", "draftId": "uuid", "op": "cancel" }
```
- `draftId`는 UUID입니다. 따로 시작 메시지는 없고, 연결에서 처음 보는 `draftId`의 메시지가 초안을 시작합니다(`points`/`erase`는 그림, `text`는 텍스트). 연결당 초안은 하나이며, 다른 `draftId`가 오면 이전 초안을 `draft:cancel`합니다.
- 클라이언트는 `config.draftIntervalMs`(기본 100ms)마다 최대 1번 보냅니다. 같은 획의 다음 묶음은 앞 묶음의 마지막 점으로 시작합니다. 그리는 동안 `/app/cursor`는 보내지 않습니다(초당 20회 한도).
- 서버 검증(SEC-02): op마다 정해진 필드만(그 밖의 필드는 `VALIDATION_FAILED`), 좌표는 캔버스 안, `c`는 팔레트 인덱스, `w`는 허용 굵기, 한 묶음의 bbox(굵기의 절반만큼 확장)는 `maxBbox` 이하, `text`의 bbox는 캔버스 안이고 `maxBbox` 이하. 한 초안의 누적 본문이 2MB(`max-payload-bytes`)를 넘으면 `PAYLOAD_TOO_LARGE`. 거절은 모두 `/user/queue/errors`로 가고 연결은 유지됩니다.
- 텍스트 초안의 글자는 **서버로도 보내지 않습니다**. 다른 사용자에게는 "작성 중" 상자만 보입니다(D-33).
- 서버는 초안 내용을 보관하지 않습니다. 연결 상태에 초안 id, 종류, 닿은 청크, 누적 바이트만 둡니다(INV-06). 연결이 끊기면 닿았던 청크에 `draft:cancel`을 보냅니다.

### 브로드캐스트 이벤트 (서버 → 클라이언트)

청크 토픽 (`/topic/page.{p}.chunk.{i}`)
```json
{ "type": "doodle:published", "doodle": { "id": "9001", "kind": "DRAWING", "bbox": { "x": 466, "y": 6, "w": 28, "h": 10 }, "payload": { ... } } }   // bbox가 걸치는 청크마다, 작성자 정보 없음
{ "type": "doodle:removed", "id": "9001" }
{ "type": "draft:update", "sid": "s-ab12", "draftId": "uuid", "op": "points", "strokeIndex": 0, "c": 3, "w": 8, "points": [1200,340,1203,342] }
{ "type": "draft:update", "sid": "s-ab12", "draftId": "uuid", "op": "erase", "strokeIndex": 0 }
{ "type": "draft:update", "sid": "s-ab12", "draftId": "uuid", "op": "text", "bbox": { "x": 1500, "y": 900, "w": 52, "h": 34 } }
{ "type": "draft:cancel", "sid": "s-ab12", "draftId": "uuid" }
{ "type": "cursor", "sid": "s-ab12", "nickname": "익명의 펭귄 #3842", "color": "#4F8EF7", "x": 1200, "y": 800 }
{ "type": "cursor:leave", "sid": "s-ab12" }
```
- `sid`는 연결별 임시 ID입니다. 사용자 ID는 노출하지 않습니다.
- `points`와 `text`는 그 묶음(상자)이 닿는 청크에, `erase`와 `draft:cancel`은 그 초안이 닿았던 모든 청크에 갑니다. 두 청크를 보는 사람은 같은 묶음을 두 번 받을 수 있습니다(클라이언트가 거름). 보는 쪽은 `draftIdleTimeoutSec` 동안 소식이 없는 초안을 지웁니다.

메타 토픽 (`/topic/page.{p}.meta`)
```json
{ "type": "page:status", "status": "COUNTDOWN", "occupancyPermille": 712, "countdownStartedAt": "...", "scheduledEndAt": "..." }
{ "type": "minimap:diff", "set": [1203, 1204], "clear": [77], "occupancyPermille": 713 }        // 칸 인덱스 = row * 160 + col
{ "type": "page:turned", "newPageId": "13" }
```

개인 큐 (`/user/queue/notice`)
```json
{ "type": "banned", "reason": "..." }     // 수신 후 서버가 연결 종료
```

### STOMP 에러
`ERROR` 프레임의 `message` 헤더에 REST와 같은 에러 코드(`UNAUTHENTICATED`, `CONSENT_REQUIRED`, `BANNED`, `FORBIDDEN`, `PAGE_ENDED`, `RATE_LIMITED`, `VALIDATION_FAILED`)를 넣습니다. 본문은 같은 코드의 `text/plain`입니다. 클라이언트는 `PAGE_ENDED`를 받으면 현재 페이지를 다시 불러옵니다.

---

## 7. 내부 API: Spring → ML 서버 (D-40)

> Docker 내부 네트워크에서만 접근합니다(`http://ml:8000`). 외부에 포트를 열지 않고, nginx도 이 경로를 프록시하지 않습니다(SEC-17).
> 요청과 응답 스키마를 바꾸면 `ml/serving`의 스키마, Spring의 `MlModerationProvider`, 이 절을 **같은 PR에서** 함께 고칩니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/v1/moderate` | 그림 doodle PNG 판정 (이미지 분류 + OCR) |
| GET | `/health` | 모델 로드 여부와 버전 |

`POST /v1/moderate` 요청 (`multipart/form-data`)
- `image`: PNG (Spring의 `DoodleRenderer`가 렌더링한 그림 doodle, 최대 1024×1024). 텍스트 doodle은 보내지 않습니다
- `requestId`: 분석마다 Spring이 만드는 UUID (로그 상관관계용, 사용자 정보 아님)

응답 `200`
```json
{
  "modelVersion": "clip-zs-2026.11.01",
  "image": {
    "label": "FLAGGED",
    "scores": { "SEXUAL": 0.81, "HATE": 0.04 }
  },
  "ocr": { "text": "인식된 글자", "confidence": 0.62 },
  "latencyMs": 840
}
```
- `scores`의 키는 `models.lock`의 `[image.thresholds]` 키와 같습니다(`docs/FILTER.md` 카테고리 이름). 카테고리마다 독립인 0~1 점수이며 `safe` 점수는 없습니다.
- `label`은 ML 서버가 `models.lock`의 임계값으로 정합니다. Spring은 `scores`도 그대로 `ml_prediction`에 저장합니다.
- `ocr.text`는 OCR이 찾은 글자 덩어리 중 신뢰도가 `models.lock`의 `[ocr] min_confidence` 이상인 것만 읽는 순서(위→아래, 왼→오)로 공백 연결한 것입니다. `ocr.confidence`는 남은 덩어리의 평균 신뢰도이고, 남은 덩어리가 없으면 `""`와 `0`입니다. `models.lock`의 OCR이 `"none"`이면 항상 `""`입니다.
- `ocr.text`가 비어 있지 않으면 Spring이 텍스트 검사 경로(금칙어 → ChatKHU → OpenAI)로 넘깁니다(D-35).
- 오류: `400`(이미지 아님, 크기 초과), `503`(이미지 모델이나 OCR 미로드, 체크섬 불일치). Spring은 응답 시간 3초 초과, 4xx, 5xx를 모두 ML 판정 없음으로 처리합니다(INV-13, D-40).

`GET /health` 응답
```json
{ "status": "UP", "modelVersion": "clip-zs-2026.11.01", "ocrVersion": "easyocr-1.7.2-ko" }
```

