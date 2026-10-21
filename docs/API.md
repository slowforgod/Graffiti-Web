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
| `VALIDATION_FAILED` | 400 | 입력 검증 실패. `details.field`, `details.reason` |
| `PAYLOAD_TOO_LARGE` | 413 | 2MB 초과 |
| `MODERATION_REJECTED` | 422 | AI 검사 거부. `details.category` |
| `COOLDOWN` | 429 | 게시 쿨다운. `details.retryAfterSec` |
| `RATE_LIMITED` | 429 | 요청 속도 초과 |
| `INTERNAL` | 500 | 서버 오류 (내부 정보 노출 금지) |

---

## 1. 인증과 사용자

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/oauth2/authorization/google` | 공개 | Google 로그인 시작 (Spring Security 기본) |
| GET | `/login/oauth2/code/google` | 공개 | OAuth 콜백. 도메인 검증(SEC-04) 후 `/`로 리다이렉트. 실패하면 `/?loginError=DOMAIN_NOT_ALLOWED` |
| POST | `/api/auth/logout` | 로그인 | 세션 무효화 |
| GET | `/api/me` | 공개 | 비로그인이면 `{ "authenticated": false }` |
| POST | `/api/me/consent` | 로그인(동의 전 허용) | 개인정보 동의 기록 |

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
  "cooldownEndsAt": "2026-10-21T05:30:00.000Z"   // 쿨다운 없으면 null
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
  "countdownStartedAt": "2026-10-21T01:00:00.000Z",
  "scheduledEndAt": "2026-10-21T07:00:00.000Z",
  "serverTime": "2026-10-21T04:12:33.120Z",
  "config": {
    "width": 7680, "height": 4320, "chunkSize": 480, "cellSize": 48,
    "zoomMin": 0.5, "zoomMax": 4.0,
    "palette": ["#000000", "#FFFFFF", "..."], "strokeWidths": [2,4,8,16],
    "fontSizes": [10,11,12,13,14,15,16,20,24,32,36,40,48,64,96], "defaultFontSize": 24,
    "textMaxLength": 50, "maxBbox": 1024,
    "postCooldownSec": 1800, "countdownWarningSec": 300, "draftIdleTimeoutSec": 600
  }
}
```

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
- 종료된 페이지 ID로 요청하면 `PAGE_ENDED`를 반환합니다(아카이브 미제공, D-17).

## 3. doodle

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/api/pages/{pageId}/doodles` | 작성자 | 게시(✓). 검증 → 쿨다운 → 모더레이션 → 게시 (ARCHITECTURE 4.1) |
| GET | `/api/me/doodles?pageId=` | 로그인 | 내 doodle ID 목록 (선택 삭제, PNG 저장용) |
| DELETE | `/api/doodles/{doodleId}` | 작성자 | 내 doodle 삭제 |
| POST | `/api/doodles/delete` | 작성자 | 내 doodle 일괄 삭제 `{ "ids": ["1","2"] }` (최대 50개, 하나라도 남의 것이면 전체 거절) |

`POST /api/pages/{pageId}/doodles` 요청
```json
{ "kind": "DRAWING", "payload": { "v": 1, "strokes": [ ... ] } }
{ "kind": "TEXT", "payload": { "v": 1, "text": "안녕", "c": 0, "size": 24, "x": 1500, "y": 900 }, "bbox": { "w": 52, "h": 34 } }
```
응답
- `201` `{ "doodle": { "id", "kind", "bbox", "payload" }, "cooldownEndsAt": "..." }`
- `422 MODERATION_REJECTED` (쿨다운 소모됨, `details.cooldownEndsAt` 포함)
- `429 COOLDOWN` / `409 PAGE_ENDED` / `400 VALIDATION_FAILED` / `413 PAYLOAD_TOO_LARGE` / `403 BANNED`

검증 규칙(`DoodleValidator`, SEC-02)
- 그림: 획 1개 이상, 각 획 점 1개 이상, 좌표는 캔버스 범위 안, `c`는 팔레트 인덱스, `w`는 허용 굵기, 서버가 계산한 bbox ≤ 1024²
- 텍스트: 1~50자(줄바꿈 제외), 제어 문자 금지, `size`는 허용 목록 값, bbox 상한 검증(D-31)
- payload 본문 2MB 이하, 알 수 없는 필드는 거절

## 4. 관리자 (`/api/admin/**`, 관리자 전용)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/doodles?moderation=REJECTED\|UNCHECKED&page=0&size=20` | 거부·미검사 내역 (작성자 이메일, 닉네임, 렌더링용 payload 포함) |
| GET | `/api/admin/doodles/{id}` | doodle 상세 (작성자 이메일 포함) |
| POST | `/api/admin/doodles/{id}/restore` | REJECTED → PUBLISHED 또는 UNCHECKED 표시 해제 |
| DELETE | `/api/admin/doodles/{id}` | 아무 doodle 삭제 (종료된 페이지 포함) |
| POST | `/api/admin/doodles/delete` | 일괄 삭제 `{ "ids": [...] }` |
| GET | `/api/admin/users?sort=rejects&page=0&size=20` | AI 적발 횟수 순 사용자 |
| POST | `/api/admin/users/{id}/ban` | `{ "reason": "...", "hideCurrentPageDoodles": true }` |
| POST | `/api/admin/users/{id}/unban` | 차단 해제 |
| GET | `/api/admin/actions?page=0&size=50` | 조치 기록 (SEC-14) |

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

### 구독 (SUBSCRIBE)

| 목적지 | 권한 | 내용 |
|---|---|---|
| `/topic/page.{pageId}.chunk.{index}` | 공개 | 해당 청크의 doodle, 초안, 커서 이벤트. 현재 페이지와 0~143 범위만 허용 |
| `/topic/page.{pageId}.meta` | 공개 | 점유율, 상태, 미니맵 변경, 페이지 넘김 |
| `/user/queue/notice` | 로그인 | 개인 알림 (차단됨 등) |

### 전송 (SEND) — 모두 `작성자` 권한, 초당 20회 제한, 현재 페이지만(INV-04)

`/app/cursor`
```json
{ "pageId": "12", "x": 1200, "y": 800 }
```

`/app/draft`
```json
{ "pageId": "12", "draftId": "uuid", "op": "start",  "kind": "DRAWING", "bbox": { "x":1190,"y":330,"w":10,"h":10 } }
{ "pageId": "12", "draftId": "uuid", "op": "points", "strokeIndex": 0, "c": 3, "w": 8, "points": [1200,340,1203,342], "bbox": { ... } }
{ "pageId": "12", "draftId": "uuid", "op": "erase",  "strokeIndex": 2 }      // 초안 편집 중 지우개
{ "pageId": "12", "draftId": "uuid", "op": "text",   "text": "안녕", "c": 0, "size": 24, "x": 1500, "y": 900, "bbox": { ... } }
{ "pageId": "12", "draftId": "uuid", "op": "cancel" }
```
- 서버는 bbox가 1024² 이내이고 캔버스 범위 안인지 검사합니다. 텍스트 초안은 **AI 검사 전이므로** 다른 사용자에게 보내지 않고 "텍스트 작성 중" 박스만 중계합니다(`text` 필드 제거, SEC-05 보완).

### 브로드캐스트 이벤트 (서버 → 클라이언트)

청크 토픽 (`/topic/page.{p}.chunk.{i}`)
```json
{ "type": "doodle:published", "doodle": { "id", "kind", "bbox", "payload" } }
{ "type": "doodle:removed", "id": "9001" }
{ "type": "draft:update", "sid": "s-ab12", "draftId": "uuid", "op": "points", "...": "..." }
{ "type": "draft:cancel", "sid": "s-ab12", "draftId": "uuid" }
{ "type": "cursor", "sid": "s-ab12", "nickname": "익명의 펭귄 #3842", "color": "#4F8EF7", "x": 1200, "y": 800 }
{ "type": "cursor:leave", "sid": "s-ab12" }
```
- `sid`는 연결별 임시 ID입니다. 사용자 ID는 노출하지 않습니다.

메타 토픽 (`/topic/page.{p}.meta`)
```json
{ "type": "page:status", "status": "COUNTDOWN", "occupancyPermille": 712, "countdownStartedAt": "...", "scheduledEndAt": "..." }
{ "type": "minimap:diff", "set": [1203, 1204], "clear": [77] }        // 칸 인덱스 = row * 160 + col
{ "type": "page:turned", "newPageId": "13" }
```

개인 큐 (`/user/queue/notice`)
```json
{ "type": "banned", "reason": "..." }     // 수신 후 서버가 연결 종료
```

### STOMP 에러
`ERROR` 프레임의 `message` 헤더에 REST와 같은 에러 코드(`UNAUTHENTICATED`, `BANNED`, `PAGE_ENDED`, `RATE_LIMITED`, `VALIDATION_FAILED`)를 넣습니다.

---

## 7. 내부 API: Spring → ML 서버 (D-40)

> Docker 내부 네트워크에서만 접근합니다(`http://ml:8000`). 외부에 포트를 열지 않고, nginx도 이 경로를 프록시하지 않습니다(SEC-17).
> 요청과 응답 스키마를 바꾸면 `ml/serving`의 스키마, Spring의 `MlModerationProvider`, 이 절을 **같은 PR에서** 함께 고칩니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/v1/moderate` | 그림 doodle PNG 판정 (이미지 분류 + OCR) |
| GET | `/health` | 모델 로드 여부와 버전 |

`POST /v1/moderate` 요청 (`multipart/form-data`)
- `image`: PNG (서버가 렌더링한 doodle, 최대 1024×1024)
- `requestId`: doodle 게시 요청 ID (로그 상관관계용, 사용자 정보 아님)

응답 `200`
```json
{
  "modelVersion": "clip-zs-2026.11.01",
  "image": {
    "label": "FLAGGED",
    "scores": { "sexual": 0.81, "violence": 0.04, "hate_symbol": 0.02, "safe": 0.13 }
  },
  "ocr": { "text": "인식된 글자", "confidence": 0.62 },
  "latencyMs": 840
}
```
- `label`은 ML 서버가 `models.lock`의 임계값으로 정합니다. Spring은 `scores`도 그대로 `ml_prediction`에 저장합니다.
- `ocr.text`가 비어 있지 않으면 Spring이 텍스트 검사 경로(금칙어 → KHUChat → OpenAI)로 넘깁니다(D-35).
- 오류: `400`(이미지 아님, 크기 초과), `503`(모델 미로드). Spring은 응답 시간 3초 초과, 4xx, 5xx를 모두 ML 판정 없음으로 처리합니다(INV-13, D-40).

`GET /health` 응답
```json
{ "status": "UP", "modelVersion": "clip-zs-2026.11.01", "ocrVersion": "paddleocr-korean-x.y" }
```

