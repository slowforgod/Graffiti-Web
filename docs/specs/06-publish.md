# 06-publish: ✓로 게시, 쿨다운, 점유율 갱신, 게시 브로드캐스트

- **상태**: APPROVED
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-22 (standing 지시 "승인 받지말고 다 해"에 따라 결정 필요 1~6은 추천안으로 확정)
- **브랜치**: `feat/06-publish`
- **선행 명세**: 05

## 1. 왜 (Why)
05까지는 초안을 그리고 다른 사람에게 보여 줄 수만 있고, 캔버스에 남길 방법이 없다. 이 명세는 ✓를 눌러 초안을 doodle로 게시하고(REST), 30분 쿨다운을 큐 안에서 걸고, 점유율을 갱신하고(70%면 카운트다운), 같은 청크를 보는 사람들에게 바로 보여 준다. 모더레이션은 07에서 끼울 자리만 만들고 임시 구현을 둔다. **06만 배포하지 않는다**(INV-05, PIPELINE).

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-12 | 1인 30분 1회, 거부돼도 소모, 기준은 ✓ 누른 시각, 관리자 면제, 큐 안에서 확인 |
| 결정 | D-14, D-24 | 점유율 = 선분 bbox 칸 참조 카운트, `doodle_chunk`는 bbox가 걸치는 청크마다 1행 |
| 결정 | D-25 | 재시작 때 PUBLISHED doodle로 점유율 재계산 → `NoPublishedFootprints`를 실제 조회로 교체 |
| 결정 | D-27 | 게시는 REST, 이후 브로드캐스트는 STOMP |
| 결정 | D-28 (PROPOSED) | 검사 중 페이지가 넘어가면 `PAGE_ENDED` + 쿨다운 되돌림 |
| 결정 | D-31 (PROPOSED) | 텍스트 bbox는 클라이언트 값, 서버는 상한만 |
| 불변 조건 | INV-01/02 | 예약(③)과 게시(⑤)는 큐 안, 모더레이션(④)은 큐 밖. 큐 안 DB 쓰기는 사용자 1행, doodle 1행, 청크 ≤16행 |
| 불변 조건 | INV-03/11 | 70% 넘으면 `ACTIVE → COUNTDOWN`을 doodle INSERT와 같은 트랜잭션에서 |
| 불변 조건 | INV-04 | 경로의 `pageId`가 현재 페이지가 아니면 `PAGE_ENDED` (예약 때와 게시 때 두 번) |
| 불변 조건 | INV-05 | PASSED/UNCHECKED만 게시. 06의 임시 검사는 UNCHECKED (결정 필요 1) |
| 불변 조건 | INV-07/12 | doodle 행은 삭제하지 않고, 점유율은 DB에서 언제든 재계산 |
| 불변 조건 | INV-08 | 쿨다운은 큐 안에서 원자적 UPDATE로 확인·소모 |
| 보안 | SEC-01/02 | 파라미터 바인딩, `DoodleValidator`가 모든 필드 재검증, 작성자는 세션에서만 |
| 보안 | SEC-08/09 | 본문 2MB 상한(넘으면 읽기 중단), nginx `limit_req`(IP당 20r/s) + 쿨다운이 속도 상한 |
| 보안 | SEC-12/13 | 게시 응답·브로드캐스트에 작성자 정보 없음, 텍스트는 캔버스로만 |
| 보안 | SEC-15 | ✓마다 차단 여부를 DB에서 확인(인자 해석 + 예약 UPDATE 조건) |
| API | `API.md` §3, §6 | `POST /api/pages/{pageId}/doodles` 확정, `doodle:published` 본문 |
| DB | `DB.md` §2~4 | 스키마 변경 없음. `doodle`, `doodle_chunk` INSERT, `users.last_posted_at` 갱신 |
| 흐름 | `ARCHITECTURE.md` §4.1 | 게시 흐름 그대로, 임시 검사 단계 |

## 3. 범위
**포함**
- 백엔드
  - `doodle/DoodleController`: `POST /api/pages/{pageId}/doodles`, `작성자`. 본문을 `max-payload-bytes`까지만 읽고 넘으면 `PAYLOAD_TOO_LARGE`. 알 수 없는 필드 거절
  - `doodle/DoodleValidator`(순수): API.md §3 규칙 + bbox 계산 + footprint + 닿는 청크. 저장할 payload JSON은 검증한 값으로 다시 만든 것(정규화)
  - `doodle/PublishService`: 검증 → `ReservePost`(큐) → 검사(큐 밖) → `PublishDoodle`(큐). 실패하면 쿨다운 되돌림
  - 큐 명령 `ReservePost`, `PublishDoodle`, `ReleasePost`(page 패키지). 큐 안 DB 쓰기는 page 패키지의 포트 `PostStore` 뒤에 두고 doodle 패키지가 JDBC로 구현(결정 필요 2)
  - 게시가 커밋된 뒤 `DoodlePublished` 이벤트 → `PageEventRelay`가 닿는 청크마다 `doodle:published`
  - `moderation/ModerationService` 임시 구현: 항상 `UNCHECKED`(결정 필요 1). 07이 내부만 바꿈
  - `doodle/PublishedDoodleFootprints`: 재시작 복구용 실제 조회(`NoPublishedFootprints` 삭제)
- 프론트엔드
  - ✓ 연결: 초안 → 요청 본문, 보내는 동안 ✓/✗ 비활성, 성공하면 내 화면에 바로 추가 + 초안 `cancel`
  - 쿨다운: `me.cooldownEndsAt`과 게시 응답의 `cooldownEndsAt`, 서버 시각 차이(`serverTime`) 보정. 쿨다운 중엔 ✓ 비활성 + "N분 뒤에 게시할 수 있어요"(초안은 계속 그릴 수 있음, D-12)
  - 에러: `COOLDOWN`, `MODERATION_REJECTED`(07 대비), `VALIDATION_FAILED`, `PAYLOAD_TOO_LARGE`, `BANNED`, `INTERNAL`은 안내만 하고 초안은 남김. `PAGE_ENDED`는 기존 흐름대로 새 페이지 로딩
- 문서: `API.md` §3/§6, `ARCHITECTURE.md` §3/§4.1, `DB.md` §4(예약 UPDATE)

**제외 (NOT in scope)**
- 실제 모더레이션(금칙어, KHUChat, OpenAI, 렌더링, `moderation_action` 기록) → 07
- 내 doodle 목록, 삭제, PNG 저장 → 08
- 상단 상태바, 카운트다운 경고 → 09
- 사용자별 REST 속도 제한기 → 만들지 않음. 쿨다운(관리자 외)과 nginx IP 제한으로 충분, 11에서 다시 봄

## 4. 현재 상태 (검증된 사실)
- `PageCommandHandler.applyFootprint`: 칸 갱신, 70%면 카운트다운 UPDATE(트랜잭션), 커밋 뒤 메모리 교체와 `OccupancyChanged`/`PageStatusChanged` 이벤트. 호출하는 곳은 테스트뿐
- `PageEventRelay`가 page 이벤트를 메타 토픽으로 중계. `Broadcaster.toChunk` 있음
- `NoPublishedFootprints`가 빈 목록을 돌려줌(03 주석: 06에서 교체)
- `doodle`, `doodle_chunk` 테이블은 V4에 있고 INSERT하는 코드 없음. `ChunkDoodleQuery`가 PUBLISHED만 조회
- `MeController`가 `last_posted_at + post-cooldown`으로 `cooldownEndsAt` 계산. `last_posted_at`을 쓰는 코드 없음
- 프론트: `DraftActions`의 ✓는 `onPublish` 없이 비활성. `ChunkSync`가 `doodle:published`를 저장소에 넣음(ID로 중복 제거)

## 5. 설계

```
Client ✓                     DoodleController / PublishService           큐 (PageCommandHandler)
 │ POST /api/pages/{p}/doodles   │                                           │
 │──────────────────────────────▶│ ① @CurrentUser(AUTHOR), CSRF, 본문 ≤ 2MB    │
 │                               │ ② DoodleValidator → 정규화 payload, bbox,  │
 │                               │    footprint, 청크                          │
 │                               │── ReservePost(p, user, admin) ───────────▶│ ③ p 현재? 아니면 PAGE_ENDED
 │                               │                                           │   관리자: 통과, 기록 안 함
 │                               │                                           │   UPDATE users SET last_posted_at=now
 │                               │                                           │    WHERE id=? AND banned_at IS NULL
 │                               │                                           │    AND (last IS NULL OR last <= now-30m)
 │                               │                                           │   0행 → BANNED 또는 COOLDOWN
 │                               │◀── Reservation(previous, at) ─────────────│
 │                               │ ④ ModerationService (큐 밖, 06: UNCHECKED)  │
 │                               │── PublishDoodle(p, doodle, reservation) ─▶│ ⑤ p 현재? 아니면 되돌림 + PAGE_ENDED
 │                               │                                           │   tx: INSERT doodle, doodle_chunk
 │                               │                                           │       (+ 70%면 COUNTDOWN UPDATE)
 │                               │                                           │   커밋 뒤: grid 교체, doodle:published,
 │                               │                                           │   minimap:diff, page:status
 │                               │  실패(검사 오류, DB 오류) → ReleasePost(되돌림)   │
 │◀── 201 {doodle, cooldownEndsAt} / 429 / 409 / 400 / 413 / 403 ───────────│
```

- 되돌림(D-28): `UPDATE users SET last_posted_at = :previous WHERE id = :id AND last_posted_at = :reservedAt`. 그사이 다른 게시가 없을 때만
- 쿨다운 기준 시각 = 예약 시각 = `submitted_at`(✓ 누른 시각, D-12). `published_at`은 ⑤의 시각
- 그림 bbox: 획마다 점의 최소·최대를 굵기 절반만큼 넓히고(`floor`/`ceil`) 합친 것. 1,024² 검사는 이 값으로(05 클라이언트와 같은 식), 저장과 청크 계산은 캔버스로 자른 값(결정 필요 6)
- 텍스트 bbox: `{x, y}`는 payload, `{w, h}`는 요청의 `bbox`. D-31 상한 + 캔버스 안 + `size × 줄 수 ≤ maxBbox`(결정 필요 4)

| 파일 (예정) | 역할 |
|---|---|
| `backend/.../doodle/DoodleController.java` | REST, 본문 크기 상한, 엄격한 JSON 파싱, 응답 |
| `backend/.../doodle/DoodleValidator.java` | 순수 검증 + 정규화 + bbox/footprint/청크 |
| `backend/.../doodle/PublishService.java` | ①~⑤ 순서, 실패 시 되돌림 |
| `backend/.../doodle/JdbcPostStore.java` | `PostStore` 구현: 예약 UPDATE, 되돌림, doodle/doodle_chunk INSERT |
| `backend/.../doodle/PublishedDoodleFootprints.java` | `PublishedFootprintSource` 구현 (DB.md §4 재시작 쿼리) |
| `backend/.../page/PostStore.java` | 큐 안에서 쓰는 저장 포트 |
| `backend/.../page/PageCommand.java`, `PageCommandHandler.java` | `ReservePost`, `PublishDoodle`, `ReleasePost` |
| `backend/.../page/event/DoodlePublished.java` | 커밋 뒤 이벤트 |
| `backend/.../realtime/PageEventRelay.java`, `RealtimeEvents.java` | `doodle:published` 중계 |
| `backend/.../moderation/ModerationService.java`, `Verdict.java` | 임시 검사 (07이 교체) |
| `frontend/src/tools/publish.ts` | 초안 → 요청 본문, `POST` 호출 |
| `frontend/src/tools/cooldown.ts` | 서버 시각 보정, 남은 분 |
| `frontend/src/canvas/CanvasScreen.tsx`, `chunkSync.ts`, `ui/DraftActions.tsx` | ✓ 연결, 게시한 doodle 바로 추가, 안내 |

설정값 추가 없음(`post-cooldown`, `max-payload-bytes`, 목록들은 이미 있음).

## 6. 실패 모드
| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| ① | 본문 2MB 초과, Content-Length 없이 스트리밍 | 2MB+1바이트에서 읽기 중단 → 413 `PAYLOAD_TOO_LARGE`, 쿨다운 소모 없음 | `PublishIT` |
| ② | 알 수 없는 필드, 범위 밖 좌표, 팔레트 밖 색, 1025px, 51자, 제어 문자 | 400 `VALIDATION_FAILED` `details.field`, 쿨다운 소모 없음 | `DoodleValidatorTest`, `PublishIT` |
| ③ | 두 탭에서 동시에 ✓ | 큐가 순서대로 처리, 두 번째는 원자적 UPDATE 0행 → 429 `COOLDOWN` | `PublishIT` |
| ③ | ✓ 직전에 차단됨 | 인자 해석에서 403 `BANNED` (예약 UPDATE 조건으로 한 번 더) | `PublishIT` |
| ④ | 검사 중 예외 | 쿨다운 되돌림, 500 `INTERNAL` | `PublishServiceTest` |
| ⑤ | 검사 중 페이지 넘어감 | 쿨다운 되돌림, 409 `PAGE_ENDED` → 클라이언트가 새 페이지 로딩 | `PublishIT` |
| ⑤ | DB INSERT 실패 | 트랜잭션 롤백, 메모리·이벤트 그대로, 쿨다운 되돌림, 500 | `PageCommandHandlerTest` |
| ⑤ | 이 게시로 70% 도달 | 같은 트랜잭션에서 COUNTDOWN, `page:status` | `PublishIT` |
| 재시작 | PUBLISHED doodle의 payload | 저장된 payload로 footprint 재계산, 점유율이 재시작 전과 같음 | `PublishIT` |
| 프론트 | 응답 전에 ✓ 연타 | 보내는 동안 ✓/✗ 비활성 | `CanvasScreen.test` |
| 프론트 | 쿨다운 중 ✓ | ✓ 비활성 + 남은 시간. 서버 429면 안내 + 쿨다운 갱신 | `CanvasScreen.test` |

## 7. 수용 기준 (테스트 가능하게)
- [ ] AC-1: 작성자가 유효한 그림을 게시하면 201 `{doodle:{id,kind,bbox,payload}, cooldownEndsAt}`(= 예약 시각 + 30분). `doodle`은 PUBLISHED/UNCHECKED, `submitted_at` = 예약 시각, bbox는 서버 계산값, `doodle_chunk`는 bbox가 걸치는 청크마다 1행
- [ ] AC-2: 그 청크를 구독한 다른 클라이언트가 `{type:"doodle:published", doodle:{id,kind,bbox,payload}}`를 받는다(키는 정확히 그것뿐, 작성자 정보 없음). 메타 토픽에 `minimap:diff`
- [ ] AC-3 (D-12, INV-08): 30분 안의 두 번째 게시는 429 `COOLDOWN` `details.retryAfterSec`. 관리자는 쿨다운 없이 게시되고 `last_posted_at`이 바뀌지 않음
- [ ] AC-4 (D-28): 예약 뒤 게시 전에 페이지가 넘어가면 409 `PAGE_ENDED`이고 `last_posted_at`이 이전 값으로 돌아간다
- [ ] AC-5 (INV-04): 끝난 `pageId`로 게시 → 409 `PAGE_ENDED`, 쿨다운 소모 없음
- [ ] AC-6 (SEC-02): 알 수 없는 필드, 빈 획 목록, 홀수 개 좌표, 캔버스 밖 좌표, 팔레트 밖 색, 없는 굵기, 1,024px 넘는 bbox, 그림에 `bbox`, 0자·51자·공백만·제어 문자 텍스트, 목록 밖 글자 크기, D-31 상한을 넘는 텍스트 bbox → 400 `VALIDATION_FAILED`, 쿨다운 소모 없음
- [ ] AC-7 (SEC-08): 2MB 넘는 본문 → 413 `PAYLOAD_TOO_LARGE`
- [ ] AC-8 (SEC-07/15): 비로그인 401, 동의 전 403 `CONSENT_REQUIRED`, 차단 403 `BANNED`, CSRF 토큰 없으면 403
- [ ] AC-9 (INV-03): 게시로 점유율이 70%를 넘으면 같은 처리에서 페이지가 COUNTDOWN이 되고 `page:status`가 나간다
- [ ] AC-10 (D-25, INV-12): 재시작(복구)하면 PUBLISHED doodle로 계산한 점유율이 게시 직후와 같다
- [ ] AC-11: ✓를 누르면 초안이 내 화면에서 바로 doodle로 바뀌고(`cancel` 전송), 다른 창에도 보인다
- [ ] AC-12 (D-12): 쿨다운 중엔 ✓가 비활성이고 남은 분이 보인다. 초안은 계속 그릴 수 있다. 서버 시각 기준
- [ ] AC-13: 게시 실패(쿨다운, 검증, 크기, 차단, 서버 오류)면 안내가 보이고 초안은 남는다. `PAGE_ENDED`면 새 페이지를 불러온다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | 검증 규칙, bbox, 정규화 payload, 청크 | `doodle/DoodleValidatorTest` |
| 단위 | 큐 명령(예약, 되돌림, 게시, 카운트다운, DB 실패) | `page/PageCommandHandlerTest` |
| 단위 | 순서와 되돌림(검사 예외, PAGE_ENDED) | `doodle/PublishServiceTest` |
| 통합 | REST + MySQL + STOMP: AC-1~AC-10 | `doodle/PublishIT` |
| 단위(프론트) | 요청 본문 만들기, 쿨다운 계산 | `publish.test.ts`, `cooldown.test.ts` |
| 컴포넌트(프론트) | ✓ 성공·실패·쿨다운·보내는 중 | `CanvasScreen.test.tsx` |
| QA | 헤드리스 Chromium 두 개: 작성자가 ✓ → 두 창 모두 doodle, 쿨다운 표시, 재시작 뒤에도 남음 | 수동 체크리스트 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 큐 안에서 모더레이션, 렌더링, 외부 HTTP를 하지 않는다(INV-02). 큐 안 DB 쓰기는 한 사용자 1행, doodle 1행, 청크 ≤16행
- 커밋 뒤에만 메모리 교체와 이벤트(ARCHITECTURE §4.1)
- 게시 응답과 이벤트에 `author_id`, 닉네임을 넣지 않는다(SEC-13, D-22). 로그에 payload, 텍스트를 남기지 않는다
- 수치는 config에서(INV-10)

## 10. 롤백
PR revert. 스키마 변경 없음. 이미 게시된 doodle 행은 남지만(INV-07) 05 코드는 그대로 읽어서 보여 준다.

## 결정 필요 (승인 시 함께 확정)
1. 임시 검사 결과 — **추천: `UNCHECKED`.** "항상 통과"(PIPELINE)를 PASSED로 저장하면 검사하지 않은 낙서가 검사된 것처럼 남는다. UNCHECKED는 INV-05상 게시되고, 10의 관리자 화면에서 미검사로 보이며, D-39 지표를 흐리지 않는다
2. 큐 안 저장 위치 — **추천: page 패키지에 `PostStore` 포트, doodle 패키지가 JDBC 구현.** 큐(page)가 doodle 패키지에 의존하지 않고, 테스트에서 실패를 주입하기 쉽다
3. 되돌림 조건 — **추천: `last_posted_at`이 예약 시각 그대로일 때만 이전 값으로.** 관리자가 그사이 뭔가 바꾼 경우를 덮어쓰지 않음. 검사 예외와 DB 실패도 사용자 잘못이 아니므로 PAGE_ENDED와 같이 되돌림
4. 텍스트 추가 규칙 — **추천: 공백만인 글자 거절, `size × 줄 수 ≤ maxBbox`.** D-31은 상한만 있어 줄바꿈 수천 개로 상자 밖에 그리는 요청을 막지 못함. 05 클라이언트는 이미 이 범위 안에서만 만든다
5. 실패 후 초안 — **추천: 남김**(PAGE_ENDED 제외). 쿨다운이면 기다렸다가 다시 ✓할 수 있게. 거부(07)도 일단 남기고 07에서 다시 판단
6. 그림 bbox — **추천: 상한 검사는 자르지 않은 값, 저장은 캔버스로 자른 값.** 클라이언트(05)와 같은 식으로 검사해야 클라이언트가 만든 초안이 서버에서 거절되지 않고, 저장 bbox는 청크 계산과 `bbox_x ≥ 0`을 위해 캔버스 안이어야 함

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
