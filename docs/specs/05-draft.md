# 05-draft: 그리기 도구, 초안 편집, 초안 중계

- **상태**: IN_PROGRESS (PR 대기)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-22 (standing 지시 "승인 받지말고 다 해"에 따라 결정 필요 1~6은 추천안으로 확정)
- **브랜치**: `feat/05-draft`
- **선행 명세**: 04

## 1. 왜 (Why)
04까지는 캔버스를 보기만 할 수 있다. 이 명세는 툴바(이동, 펜, 텍스트, 지우개)와 색·굵기·글자 크기 선택, 초안 편집(✓/✗), 그리고 그리는 과정을 다른 사람에게 반투명하게 보여 주는 초안 중계를 만든다. 초안은 어디에도 저장하지 않고(INV-06), 10분 무동작이나 연결 끊김이면 사라진다(D-11). ✓로 실제 게시하는 일은 06이 이 초안을 받아서 한다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-07 | 팔레트 12색, 굵기 4종, 글자 크기 목록·기본 24, 50자, 모드 툴바, 조작(한 손가락 = 모드, 두 손가락 = 이동·줌, 스페이스+드래그) |
| 결정 | D-10 | 초안 bbox 오른쪽 위의 ✓/✗. 텍스트와 그림은 다른 초안 |
| 결정 | D-11 | 반투명 실시간 중계, bbox 1,024² 상한, 초안당 2MB 안전장치, 1px 미만 점 버림, 지우개는 초안 전용, 10분 무동작·연결 끊김이면 취소 |
| 결정 | D-12 | 사용자별 STOMP 전송 초당 20회 → 초안 전송 간격을 100ms로 (결정 필요 1) |
| 결정 | D-21 | 펜을 처음 잡을 때 로그인 |
| 결정 | D-29 (PROPOSED) | 증분만 중계, 늦게 들어온 사람은 앞부분을 못 봄 |
| 결정 | D-33 | 텍스트 초안은 글자 없이 위치와 크기만 중계 |
| 불변 조건 | INV-04 | `/app/draft`의 `pageId`가 현재 페이지가 아니면 `PAGE_ENDED` |
| 불변 조건 | INV-06 | 서버는 초안 내용을 보관하지 않음. 연결 상태에 초안 id, 종류, 닿은 청크 번호, 누적 바이트만 |
| 불변 조건 | INV-10 | 전송 간격, 상한, 무동작 시간은 `GraffitiProperties` → config |
| 보안 | SEC-02 | 서버가 좌표·색·굵기·bbox·초안 id 형식을 다시 검사, 알 수 없는 필드 거절 |
| 보안 | SEC-07/08 | 04의 인터셉터(작성자 권한, 초당 20회, 64KB)가 `/app/draft`에도 그대로 적용 |
| 보안 | SEC-12 | 다른 사람의 초안·텍스트는 캔버스로만 그림. 텍스트 초안의 글자는 서버로도 보내지 않음 |
| 보안 | SEC-13 | `draft:*` 이벤트에 사용자 ID 없음(`sid`만) |
| API | `API.md` §6 | `/app/draft` 메시지 확정(`start` 없음, `text`는 bbox만), `draft:update`/`draft:cancel` 본문, config `draftIntervalMs` |
| 흐름 | `ARCHITECTURE.md` §4.3 | 전송 간격 100ms, 취소 경로 |

## 3. 범위
**포함**
- 백엔드 (`realtime/`)
  - `DraftController` (`/app/draft`): 메시지마다 현재 페이지, 형식 검사 → 청크 토픽으로 `draft:update` 중계. `cancel`과 연결 종료 → 그 초안이 닿은 모든 청크에 `draft:cancel`
  - `DraftRules`(순수): 메시지 검증(op별 필드, 범위, 팔레트, 굵기, bbox 상한, UUID 형식)과 닿는 청크 계산
  - 연결당 초안 1개: 다른 `draftId`의 메시지가 오면 이전 초안을 `draft:cancel`하고 새 초안으로. 취소된 직후 늦게 도착한 같은 id의 메시지는 버림
  - 초안당 누적 바이트가 `max-payload-bytes`(2MB)를 넘으면 그 메시지는 중계하지 않고 `/user/queue/errors`에 `PAYLOAD_TOO_LARGE`
  - 검증 실패는 04와 같이 연결 유지 + `/user/queue/errors`. `@MessageExceptionHandler`를 `CursorController`에서 공용 `RealtimeErrorAdvice`로 옮김
  - 설정 `graffiti.realtime.draft-interval`(100ms) → config `draftIntervalMs`
- 프론트엔드 (`tools/`, `ui/`, `canvas/`, `realtime/`)
  - 툴바: 이동 / 펜 / 텍스트 / 지우개, 팔레트 12색, 굵기 4종(펜), 글자 크기(텍스트, 기본값 `defaultFontSize`)
  - 그리기 도구는 `작성자`만: 비로그인이면 로그인으로 이동(D-21), 동의 전은 04의 동의 화면, 차단은 버튼 비활성 + 안내, 연결 전에는 비활성("연결 중")
  - 펜: 포인터 다운 → 획 시작, 이동 → 1px 미만 점 버림, 초안 bbox가 `maxBbox`를 넘을 점은 버리고 안내. 여러 획이 한 초안
  - 지우개: 초안의 획 중 포인터가 닿은 획을 통째로 지움(게시된 doodle에는 작동 안 함)
  - 텍스트: 클릭한 자리에 입력 상자, 여러 줄, 줄바꿈 제외 `textMaxLength`자, 상자 크기는 측정값
  - ✓/✗: 초안 bbox 오른쪽 위. ✗ = 취소. ✓는 06에서 연결(05에서는 비활성)
  - 초안 하나가 있는 동안 다른 종류의 도구(펜·지우개 ↔ 텍스트)는 비활성. 이동은 언제나 가능
  - 조작: 펜·지우개·텍스트 모드에서 한 손가락/마우스는 그 모드, 두 손가락은 이동·줌, 스페이스+드래그는 이동, 휠은 줌(D-07)
  - 중계 전송: `draftIntervalMs`마다 새 점을 묶어 보냄(앞 묶음의 마지막 점을 첫 점으로 포함해 선이 이어짐). 그리는 동안 커서 전송은 멈춤
  - 무동작 `draftIdleTimeoutSec` → 내 초안 취소 + 안내, 연결 끊김 → 내 초안 폐기
  - 다른 사람 초안: 청크 이벤트 `draft:update`/`draft:cancel`을 반투명으로 그림. 텍스트 초안은 점선 상자 + "작성 중…". `draftIdleTimeoutSec` 동안 소식이 없으면 지움. 내 초안의 메아리는 `draftId`로 거름
- 문서: `API.md` §6, `ARCHITECTURE.md` §3/§4.3/§6

**제외 (NOT in scope)**
- ✓ 게시(REST, 검증, 쿨다운, 브로드캐스트) → 06
- 선택 모드(내 doodle 선택·삭제) → 08
- 페이지 넘김 경고와 넘김 시 초안 폐기 안내 → 09 (05에서도 새 페이지를 불러오면 화면이 새로 만들어져 초안은 사라짐)
- 늦게 들어온 사람에게 초안 스냅샷 → TODOS(이미 있음, D-29)
- 게시된 doodle 부분 지우기 → MVP 제외(D-11, TODOS 있음)
- 텍스트 초안 이동·크기 조절 → 위치는 클릭한 자리 고정. 다시 쓰려면 ✗ 후 새로 클릭 (TODOS 추가 안 함: 필요해지면 그때 판단)

## 4. 현재 상태 (검증된 사실)
- 04(PR #32, main 머지): `RealtimeInterceptor`가 `/app/**` SEND마다 작성자 권한, 초당 한도, 64KB를 검사. `ConnectionState`(sid, 구독, 작성자, 커서 청크), `Broadcaster.toChunk`, `CursorController`(검증 실패 → `/user/queue/errors`)
- `GraffitiProperties.Doodle`: `maxBbox`, `maxPayloadBytes`, `palette`, `strokeWidths`, `fontSizes`, `defaultFontSize`, `textMaxLength`, `draftIdleTimeout` 있음. config로도 나감(`PageController.ClientConfig`)
- 프론트: `CanvasView`는 드래그가 항상 이동, `ChunkSync`가 청크 이벤트 중 `doodle:*`, `cursor*`만 처리하고 나머지는 무시, `RealtimeLink`에 연결 끊김 알림 없음, `tools/` 폴더 없음
- 클라이언트 inbound 채널은 스레드 풀이라 한 연결의 메시지도 동시에·순서가 바뀌어 처리될 수 있음(`setPreserveReceiveOrder`는 04에서 ERROR 프레임 문제로 끔)

## 5. 설계

```
작성자 브라우저                                     Spring (큐를 거치지 않음, INV-01 대상 아님)
 펜 다운 ─ draftId = UUID ─┐
 100ms마다 새 점 묶음 ─────┼─ SEND /app/draft {pageId, draftId, op:"points", strokeIndex, c, w, points}
 지우개                ────┼─ SEND {op:"erase", strokeIndex}
 텍스트 입력           ────┼─ SEND {op:"text", bbox}            (글자는 보내지 않음, D-33)
 ✗ / 10분 무동작       ────┴─ SEND {op:"cancel"}
                               │
                               ▼ RealtimeInterceptor (04): 작성자? 초당 20회? 64KB?
                             DraftController (연결 상태에 synchronized)
                               ├ pageId ≠ 현재            → errors: PAGE_ENDED
                               ├ DraftRules.check 실패     → errors: VALIDATION_FAILED
                               ├ draftId ≠ 지금 초안       → 지금 초안 draft:cancel, 새 초안으로
                               │   (방금 취소한 id면 버림)
                               ├ 누적 바이트 > 2MB         → errors: PAYLOAD_TOO_LARGE
                               └ 중계: points/text → 그 bbox가 닿는 청크들, erase → 닿았던 모든 청크
                                       /topic/page.p.chunk.i  {type:"draft:update", sid, draftId, op, ...}
                                 cancel → 닿았던 모든 청크 {type:"draft:cancel", sid, draftId}, 초안 비움
 연결 끊김 (SessionDisconnectEvent) → 남은 초안이 있으면 draft:cancel

 연결 상태(세션 속성)에 남는 것: draftId, 종류, 닿은 청크 번호 집합, 누적 바이트, 방금 취소한 id (INV-06: 점·글자 없음)

보는 브라우저: ChunkSync ─ draft:* (버퍼링 없이 바로) ─▶ RemoteDrafts ─▶ 반투명 레이어
   points: strokeIndex별 선분 묶음에 추가 / erase: 그 획 제거 / text: 점선 상자 / cancel: 제거
   draftIdleTimeoutSec 동안 소식 없음 → 제거, 청크를 벗어나면 그 청크로만 본 초안 제거
```

내 초안의 상태 (`tools/draft.ts`, 순수)
```
없음 ──펜 다운──▶ 그림 초안(획 추가 중) ◀──펜 다운/업, 지우개──▶ 그림 초안
  └──텍스트 클릭──▶ 텍스트 초안(입력 중)
그림/텍스트 초안 ──✗, 무동작, 연결 끊김, 페이지 다시 불러옴──▶ 없음
                ──✓ (06)──▶ 게시 요청
```

| 파일 (예정) | 역할 |
|---|---|
| `backend/.../realtime/DraftController.java` | `/app/draft` 중계, 연결 종료 시 취소 |
| `backend/.../realtime/DraftRules.java` | 메시지 검증과 청크 계산 (순수) |
| `backend/.../realtime/DraftMessage.java` | 메시지 본문 (알 수 없는 필드 거절) |
| `backend/.../realtime/RealtimeErrorAdvice.java` | `@MessageMapping` 검증 실패 → `/user/queue/errors` (커서와 공용) |
| `backend/.../realtime/ConnectionState.java` | 초안 메타데이터 필드 추가 |
| `backend/.../realtime/RealtimeEvents.java` | `DraftUpdate`, `DraftCancel` |
| `backend/.../config/GraffitiProperties.java`, `application.yml`, `page/PageController.java` | `draft-interval` → `draftIntervalMs` |
| `frontend/src/tools/draft.ts` | 내 초안 모델: 점 추가(1px, bbox 상한), 지우기 판정, bbox, 텍스트 상자 |
| `frontend/src/tools/draftSender.ts` | 묶음 전송(간격, 이어지는 첫 점), 취소 |
| `frontend/src/tools/useDraft.ts` | 위를 묶은 React 훅 (도구 상태, 연결 끊김, 무동작 타이머) |
| `frontend/src/tools/textMeasure.ts` | 텍스트 상자 크기 측정 (Konva, 측정 불가면 D-31 상한식) |
| `frontend/src/canvas/remoteDrafts.ts` | 다른 사람 초안 상태 |
| `frontend/src/canvas/DraftLayer.tsx` | 내 초안 + 다른 사람 초안 그리기 |
| `frontend/src/canvas/CanvasView.tsx` | 모드별 포인터 처리, 스페이스 이동 |
| `frontend/src/canvas/chunkSync.ts` | `draft:*` 처리 |
| `frontend/src/ui/Toolbar.tsx` | 모드, 색, 굵기, 글자 크기 |
| `frontend/src/ui/DraftActions.tsx` | ✓/✗ |
| `frontend/src/ui/TextDraftEditor.tsx` | 텍스트 입력 상자 |
| `frontend/src/realtime/link.ts`, `connection.ts`, `fakeLink.ts` | `onDisconnect` |

- 설정값: `graffiti.realtime.draft-interval` = 100ms (INV-10), config `draftIntervalMs`
- Search Before Building: Spring의 `@ControllerAdvice` + `@MessageExceptionHandler`가 모든 `@MessageMapping`에 적용됨(Spring Framework 문서 "Annotated Controllers > Exceptions") → 커서·초안이 같은 처리기를 씀. 한 연결 안의 순서는 보장되지 않으므로(`setPreserveReceiveOrder`를 쓰지 않음) 연결 상태 변경은 `synchronized`로 묶음

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| SEND | 비로그인·동의 전·차단의 `/app/draft` | 04 인터셉터가 ERROR, 중계 없음 | `RealtimeIT` |
| SEND | 끝난 `pageId` | `/user/queue/errors` `PAGE_ENDED`, 연결 유지 | `RealtimeIT` |
| SEND | 캔버스 밖 좌표, 팔레트 밖 색, 없는 굵기, 홀수 개 점, 1025px 묶음, 잘못된 UUID, 알 수 없는 op·필드 | `VALIDATION_FAILED`, 중계 없음 | `DraftRulesTest`, `RealtimeIT` |
| SEND | 한 초안이 2MB를 넘게 보냄 | 그 뒤 메시지는 `PAYLOAD_TOO_LARGE`, 중계 없음(사용자 화면엔 변화 없음) | `RealtimeIT` |
| SEND | 같은 연결의 메시지가 순서가 바뀌어 처리 (취소 뒤 늦은 points) | 방금 취소한 id는 버림 → 다른 사람에게 유령 초안 안 생김 | `RealtimeIT` |
| SEND | 그리는 중 커서까지 보내 초당 20회 초과 | 그리는 동안 커서 전송 멈춤, 초안은 100ms 간격 | `draftSender.test`, `CanvasScreen.test` |
| 연결 | 작성자 연결이 끊김 | 서버가 닿았던 청크에 `draft:cancel`, 작성자 화면은 초안 폐기 + 안내 | `RealtimeIT`, `CanvasScreen.test` |
| 무동작 | ✓도 ✗도 없이 10분 | 작성자 화면이 취소를 보내고 안내 "10분 동안 움직임이 없어 초안을 지웠어요" | `CanvasScreen.test` |
| 무동작 | 작성자 화면이 취소를 못 보냄(탭 정지 등) | 보는 쪽이 10분 소식 없는 초안을 지움 | `remoteDrafts.test` |
| 그리기 | bbox가 1,024를 넘으려 함 | 넘는 점을 버리고 안내 "낙서 하나는 1024px 안에 그려요" | `draft.test` |
| 그리기 | 비로그인이 펜을 누름 | 로그인으로 이동 | `Toolbar.test` |
| 그리기 | 연결 전·차단 | 그리기 도구 비활성 + 이유 표시 | `Toolbar.test` |
| 그리기 | 그리는 중 두 번째 손가락 | 지금 획을 끝내고 이동·줌 | 수동 QA |
| 보기 | 구독 전에 시작된 초안 | 구독 후 묶음부터 보임(D-29) | `remoteDrafts.test` |
| 보기 | 내 초안 메아리 | `draftId`로 걸러 두 번 그리지 않음 | `CanvasScreen.test` |
| 보기 | 다른 청크로 이동해 구독 해제 | 그 청크에서만 본 초안 제거 | `remoteDrafts.test` |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1 (D-11, D-29): 작성자 A가 `/app/draft {op:"points", ...}`를 보내면 그 점 묶음이 닿는 청크 구독자 B가 `{type:"draft:update", sid, draftId, op:"points", strokeIndex, c, w, points}`를 받는다. 키는 정확히 그것뿐(사용자 ID 없음)
- [x] AC-2: A가 `cancel`을 보내거나 연결을 끊으면, 초안이 닿았던 모든 청크에 `draft:cancel {sid, draftId}`가 간다. 다른 `draftId`로 새로 그리면 이전 초안이 취소된다
- [x] AC-3 (D-33): `text` 메시지는 bbox만 중계되고, 본문에 `text` 필드가 오면 `VALIDATION_FAILED`
- [x] AC-4 (SEC-02): 캔버스 밖 좌표, 팔레트 밖 색, 없는 굵기, 홀수 개 점, 굵기 포함 1025px 묶음, UUID가 아닌 `draftId`, 알 수 없는 op나 필드 → 보낸 사람의 `/user/queue/errors`에 `VALIDATION_FAILED`, 구독자는 아무것도 받지 않음, 연결 유지
- [x] AC-5 (INV-04): 끝난 `pageId` → `PAGE_ENDED` (errors 큐)
- [x] AC-6 (D-11): 한 초안의 누적 본문이 2MB를 넘으면 그 메시지부터 `PAYLOAD_TOO_LARGE`, 중계 없음
- [x] AC-7 (SEC-07): 비로그인의 `/app/draft`는 ERROR `UNAUTHENTICATED`
- [x] AC-8 (D-11): 클라이언트는 이전 점과 1px 미만인 점을 버리고, 초안 bbox(굵기 포함)가 `maxBbox`를 넘게 하는 점을 버린다
- [x] AC-9: 묶음 전송은 `draftIntervalMs`마다 한 번 이하이고, 두 번째 묶음부터는 앞 묶음의 마지막 점으로 시작한다. 그리는 동안 커서는 보내지 않는다
- [x] AC-10 (D-11): 지우개가 닿은 내 초안 획은 통째로 사라지고 `erase`가 나간다. 게시된 doodle은 지워지지 않는다
- [x] AC-11 (D-11): ✗를 누르면 초안이 사라지고 `cancel`이 나간다. 마지막 동작 후 `draftIdleTimeoutSec`가 지나면 같은 일이 일어나고 안내가 보인다. 연결이 끊기면 초안이 사라진다
- [x] AC-12 (D-21): 비로그인 사용자가 펜·텍스트·지우개를 누르면 로그인 주소로 이동한다. 차단된 사용자와 연결 전에는 버튼이 비활성
- [x] AC-13 (D-10): 초안이 있으면 bbox 오른쪽 위에 ✓/✗가 보인다. ✓는 06 전까지 비활성. 그림 초안이 있는 동안 텍스트 도구는 비활성(반대도)
- [x] AC-14 (D-07, INV-10): 팔레트, 굵기, 글자 크기 목록과 기본값, 50자 제한은 config에서 온다
- [x] AC-15: 보는 쪽은 다른 사람 초안을 반투명으로 그리고, 텍스트 초안은 "작성 중…" 상자로만 보이며 글자는 없다. 내 초안의 메아리는 그리지 않는다
- [x] AC-16: 브라우저 두 개(작성자 1, 비로그인 1)에서 작성자가 그리는 선이 다른 창에 반투명으로 따라 나오고, ✗나 창 닫기로 사라진다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | 메시지 검증, 청크 계산 | `realtime/DraftRulesTest` |
| 통합 | 실제 STOMP: 중계 키, 청크 범위, 취소·연결 종료·초안 교체, 거절 코드, 2MB, 늦은 메시지 | `realtime/RealtimeIT` |
| 단위(프론트) | 초안 모델(1px, bbox 상한, 지우기, 텍스트 상자), 묶음 전송, 무동작 타이머, 다른 사람 초안 | `draft.test.ts`, `draftSender.test.ts`, `remoteDrafts.test.ts` (무동작 타이머는 `CanvasScreen.test.tsx`) |
| 단위(프론트) | `ChunkSync`가 `draft:*`를 버퍼 없이 넘김 | `chunkSync.test.ts` |
| 컴포넌트(프론트) | 툴바 권한·비활성, ✗와 무동작 취소, 연결 끊김, 메아리 거르기, 그리는 동안 커서 멈춤 | `Toolbar.test.tsx`, `CanvasScreen.test.tsx` |
| QA | 헤드리스 Chromium 두 개(작성자 1, 비로그인 1): 펜으로 그리면 다른 창에 반투명으로 보임, 지우개, 텍스트 상자, ✗, 창 닫기 | 수동 체크리스트 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 초안 점과 글자를 서버 어디에도(필드, 캐시, 로그) 남기지 않는다(INV-06). 연결 상태엔 메타데이터만
- 초안 중계는 큐를 거치지 않는다(페이지 상태를 바꾸지 않음). 현재 페이지 확인은 `LivePage`
- 텍스트 초안의 글자는 서버로 보내지 않는다(D-33). 입력 중인 글자는 내 화면의 입력 상자에만 있음
- 수치는 config에서 읽는다(INV-10). 화면 표시용 크기(✓ 버튼 등)는 예외

## 10. 롤백
PR revert. DB 변경 없음. 프론트는 04의 보기 전용 화면으로 돌아간다.

## 결정 필요 (승인 시 함께 확정)
1. 초안 전송 간격 — **추천: 100ms(`draft-interval`).** ARCHITECTURE §4.3의 50ms는 초당 20회라서 D-12 한도(초당 20회)와 커서를 합치면 바로 넘고, 한도를 넘으면 연결이 닫힌다. 100ms면 초당 10회로 여유가 있고 눈으로 보기에 충분히 매끄럽다. 그리는 동안 커서는 멈춤
2. `start` 메시지 — **추천: 없앰.** 첫 `points`/`text`가 초안을 시작하고 종류는 op로 안다. 한 연결의 메시지는 순서가 바뀔 수 있어 `start`가 늦게 오면 정상 점이 거절될 수 있다. 연결당 초안은 하나
3. 10분 무동작 취소의 주체 — **추천: 작성자 화면이 취소하고, 보는 쪽도 10분 소식 없는 초안을 지움. 서버 타이머는 두지 않음.** 서버가 초안별 타이머를 갖지 않아도 두 경로가 모든 경우를 덮고, 연결 끊김은 서버가 처리
4. 텍스트 초안 — **추천: 글자를 서버로 보내지 않고 bbox만.** D-33은 다른 사람에게 보내지 말라는 결정이고, 서버가 버릴 글자를 받을 이유가 없다(API.md 예시의 `text` 필드 제거)
5. 지우개 — **추천: 닿은 초안 획을 통째로 지움.** API.md의 `erase {strokeIndex}`와 맞고, 부분 지우기는 획을 쪼개야 해서 중계 형식이 복잡해짐
6. 05에서 ✓ — **추천: 보이되 비활성.** 06이 바로 이어서 연결

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-22 자체 리뷰 (전체 diff)
  - 04에서 숨어 있던 문제: Tomcat WebSocket 텍스트 버퍼 기본값 8KB가 실제 수신 한도였다(D-27의 64KB보다 작음, 8KB 넘는 프레임은 1009로 연결 종료). `WebSocketConfig`가 컨테이너 버퍼를 `inbound-message-max-bytes`로 맞춤. 서블릿 컨테이너가 없는 테스트 컨텍스트에선 건너뜀
  - 한 연결의 프레임은 인바운드 스레드 풀에서 순서가 바뀔 수 있다 → 초안 상태 변경은 `ConnectionState`에 `synchronized`, 방금 취소한 `draftId`의 늦은 프레임은 버림(`RealtimeIT`)
  - 초안 점·글자는 서버 필드, 캐시, 로그 어디에도 없음. 연결 상태엔 id, 종류, 닿은 청크, 누적 바이트만(INV-06). 중계 본문 키는 정확히 비교(사용자 ID 없음)
  - 그리는 동안 커서 전송을 멈춰 초당 SEND가 10회 안팎(D-12 20회 한도). 2MB 테스트는 `MutableClock`을 1초씩 넘겨 속도 제한에 걸리지 않게 함
  - 수치(팔레트, 굵기, 글자 크기, 50자, 무동작 10분, 전송 간격)는 config에서 읽음. 화면 표시용 크기(지우개 반경 8px 화면 기준)만 상수
- 테스트: 백엔드 `./gradlew test` 188개 통과(실패 0, 스킵 0), 프론트 `npm test` 82개 통과, `npm run lint`·`npm run build` 통과

## QA 기록 (/gf-qa가 추가)
- 2026-10-22 로컬 스택(MySQL 8.4 컨테이너, 05 백엔드 + 테스트 로그인 엔드포인트, Vite) + 헤드리스 Chromium 컨텍스트 두 개(작성자 1, 비로그인 1)
  - 작성자가 펜으로 그린 곡선이 비로그인 창에 반투명으로 따라 나옴(`draft:update` 14~17개). ✗를 누르면 사라짐(`draft:cancel`, 두 청크라 2번)
  - 텍스트 도구: 작성자 창엔 입력 상자와 "안녕", 비활성 ✓와 ✗. 비로그인 창엔 점선 상자와 "작성 중…"만, 받은 프레임 어디에도 글자 없음
  - 처음엔 "작성 중…"이 작성자 커서 라벨에 가려져 상자 위로 옮김
  - 작성자 창을 닫으면 1.5초 안에 비로그인 창에서 텍스트 상자가 사라짐(연결 종료 → `draft:cancel`)
  - 콘솔 에러는 favicon 404뿐

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
