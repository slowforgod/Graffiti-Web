# 04-realtime: STOMP 연결과 검증, 커서, 구독 → 조회 → 병합

- **상태**: IN_PROGRESS (PR 대기)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-22 (standing 지시 "승인 받지말고 다 해"에 따라 결정 필요 1~4는 추천안으로 확정)
- **브랜치**: `feat/04-realtime`
- **선행 명세**: 03

## 1. 왜 (Why)
03까지는 새로고침해야 다른 사람의 변화가 보인다. 이 명세는 STOMP 연결을 열고, 보이는 청크와 페이지 메타를 구독해 게시·삭제·점유율·페이지 넘김을 실시간으로 받게 한다. 로딩 순서(구독 → 조회 → 병합, INV-09)를 지켜 그 사이에 일어난 변화가 빠지지 않게 하고, 모든 SUBSCRIBE와 SEND를 REST와 같은 수준으로 검증한다(SEC-07). 다른 사용자의 커서도 보인다. 05(초안 중계)와 06(게시 브로드캐스트)은 이 위에 메시지 타입만 더한다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-09 | 보이는 청크만 구독, 구독 먼저 → REST 조회 → id 병합 |
| 결정 | D-27 | 게시는 REST, 이후 브로드캐스트와 커서는 STOMP. 인바운드는 작은 메시지만(64KB) |
| 결정 | D-30 (PROPOSED) | 세션당 구독 최대 60개 |
| 결정 | D-12 | 사용자별 STOMP 전송 초당 20회 |
| 결정 | D-16 | `page:turned` 수신 → 새 페이지로 자동 전환 (초안 폐기와 안내 UI는 09) |
| 결정 | D-22 | 커서에는 닉네임과 색만, 사용자 ID 대신 연결별 `sid` |
| 불변 조건 | INV-04 | SEND와 SUBSCRIBE의 `pageId`가 현재 페이지가 아니면 거절 |
| 불변 조건 | INV-06 | 커서는 저장하지 않고 중계만 (세션 속성에 마지막 청크만) |
| 불변 조건 | INV-09 | 프론트 청크·메타 로딩 순서 |
| 불변 조건 | INV-10 | 구독 상한, 속도, 메시지 크기, 커서 전송 간격은 `GraffitiProperties` |
| 보안 | SEC-03 | 핸드셰이크 `Origin`이 `APP_ORIGIN`이 아니면 거절 |
| 보안 | SEC-07 | `ChannelInterceptor`가 CONNECT/SUBSCRIBE/SEND마다 인증, 인가, 목적지 검증 |
| 보안 | SEC-08 | 인바운드 64KB, 사용자별 초당 20회, 세션당 구독 60개 |
| 보안 | SEC-13 | 브로드캐스트에 사용자 ID, 이메일 없음. 로그에 세션 ID 없음 |
| 보안 | SEC-15 | SEND마다 DB에서 동의·차단 확인 (차단 시 연결 종료는 10) |
| API | `API.md` §6 | 구독 규칙 구체화, `minimap:diff`에 `occupancyPermille` 추가, `/user/queue/errors` 추가, config `cursorIntervalMs` |
| 흐름 | `ARCHITECTURE.md` §4.2 | 구독 → 조회 → 병합, 재연결 |

## 3. 범위
**포함**
- 백엔드 (`realtime/`)
  - `spring-boot-starter-websocket` (Boot BOM 버전, SEC-16)
  - `WebSocketConfig`: 엔드포인트 `/ws`(네이티브 WebSocket), `setAllowedOrigins(graffiti.web.allowed-origins)`, SimpleBroker(`/topic`, `/queue`, 하트비트 10초), 앱 접두사 `/app`, 사용자 접두사 `/user`, 인바운드 크기 상한 `inbound-message-max-bytes`
  - `RealtimeDestinations`(순수): 목적지 문자열 → 청크/메타/개인 큐/알 수 없음. 청크 범위 검증
  - `RealtimeInterceptor`(`ChannelInterceptor`, 인바운드):
    - CONNECT: 로그인 여부와 무관하게 허용, 연결별 `sid` 발급(세션 속성)
    - SUBSCRIBE: 청크·메타는 현재 페이지와 청크 범위만, `/user/queue/notice`와 `/user/queue/errors`는 로그인 필요, 그 밖의 목적지 거절. 세션당 `max-subscriptions` 초과 거절
    - UNSUBSCRIBE: 개수 반환
    - SEND: `/app/**`만 허용(클라이언트가 `/topic`에 직접 SEND 금지). `작성자` 권한(로그인 + 동의 + 차단 아님, 매번 DB), 사용자별 `max-messages-per-second`
  - `StompErrorHandler`: 인터셉터 거절을 ERROR 프레임 `message` 헤더의 에러 코드로(API.md §6)
  - `SendRateLimiter`(순수, `Clock`): 사용자별 1초 고정 창
  - `LivePage`: 현재 페이지 스냅샷 캐시. 페이지 이벤트가 오면 비우고, 다음 검증 때 `PageReader`로 다시 읽음(큐 스레드에서 큐를 기다리지 않도록 리스너는 비우기만)
  - `CursorController`: `/app/cursor {pageId, x, y}` → 현재 페이지·캔버스 범위 검증 → 커서가 있는 청크 토픽으로 `cursor`. 청크가 바뀌면 이전 청크에 `cursor:leave`. 검증 실패는 그 연결의 `/user/queue/errors`로 `{type:"error", code}`
  - 연결 종료(`SessionDisconnectEvent`) → 마지막 청크에 `cursor:leave`
  - `Broadcaster` 인터페이스 + `SimpBroadcaster`: 청크·메타·개인 큐 전송(06, 10이 사용)
  - `PageEventRelay`: `PageStatusChanged` → `page:status`, `OccupancyChanged` → `minimap:diff`, `PageTurned` → 옛 페이지 메타에 `page:turned`
  - 설정 `graffiti.realtime.cursor-interval`(100ms) → config `cursorIntervalMs`
- 프론트엔드 (`realtime/`, `canvas/`)
  - `@stomp/stompjs` 7.3.0 (고정)
  - `realtime/connection.ts`: `Client` 래퍼. `/ws`(현재 호스트, http→ws/https→wss), 자동 재연결, 하트비트, 연결될 때마다 구독자에게 "다시 맞춰라" 알림
  - `canvas/chunkSync.ts`(03의 `ChunkLoader` 대체): 새 청크 **구독 → 조회 → 병합**, 대기 중 이벤트는 청크별 버퍼, 응답 적용 후 버퍼 재생, 이후는 바로 적용. 벗어난 청크 구독 해제. 재연결 시 보이는 청크 전부 다시 구독·조회(응답으로 청크 내용 교체)
  - `realtime/metaSync.ts`: 메타 **구독 → 미니맵 조회 → diff 재생**, `page:status`로 점유율·상태 갱신, `page:turned` → 새 페이지 로딩
  - 커서: 다른 사람 커서를 동적 레이어에 색 점 + 닉네임으로 그림(Konva). 내가 `작성자`이면 포인터 이동을 `cursorIntervalMs`마다 `/app/cursor`로 보냄. 내 커서 이벤트는 닉네임으로 걸러 그리지 않음
- 문서: `API.md` §6, `ARCHITECTURE.md` §2/§3/§4.2/§6

**제외 (NOT in scope)**
- `/app/draft`와 초안 중계 → 05
- `doodle:published`/`doodle:removed` **발행** → 06/08/10 (04는 `Broadcaster`와 클라이언트 병합만. 병합은 테스트에서 가짜 이벤트로 확인)
- 차단 시 `/user/queue/notice`로 알리고 연결 끊기 → 10 (04는 구독 권한만)
- 상단 상태바, T−5분 카운트다운, `page:turned` 때 초안 폐기 → 09 (04는 새 페이지로 다시 로딩까지)
- SUBSCRIBE/UNSUBSCRIBE 빈도 제한 → 11 (화면 이동마다 정상적으로 수십 개가 오갈 수 있어 SEND와 같은 20회 제한을 걸 수 없음. IP당 연결 수는 nginx가 이미 10개로 제한)
- 외부 브로커 → TODOS(이미 있음)

## 4. 현재 상태 (검증된 사실)
- 03(PR #31, main에 머지됨) 위에서 작업: `PageReader`, `/api/pages/current`(config), 청크 조회, `ChunkLoader`, `DoodleStore.put/remove`
- `page/event/*`: `PageStatusChanged`, `OccupancyChanged(set, clear, occupancyPermille)`, `PageTurned`가 커밋 후 큐 스레드에서 동기 발행
- `GraffitiProperties.Realtime`: `maxMessagesPerSecond`, `maxSubscriptions`, `inboundMessageMaxBytes` 있음
- `auth/SessionUser`(Principal, 이름 = userId), `UserAccountService.find`, `CurrentUserArgumentResolver.check`(동의·차단·역할 판정, 패키지 내부)
- websocket 의존성, `realtime/` 패키지 없음. `vite.config.ts`는 `/ws`를 8080으로 프록시(ws: true)

## 5. 설계

```
브라우저 ──WS /ws (Origin 검사, 세션 쿠키 → Principal=SessionUser 또는 없음)──▶ Spring
 CONNECT        → sid 발급 (세션 속성)
 SUBSCRIBE dest → RealtimeDestinations.parse
                   ├ /topic/page.{p}.chunk.{i} : LivePage.isCurrent(p) && 0 ≤ i < 청크 수
                   ├ /topic/page.{p}.meta      : LivePage.isCurrent(p)
                   ├ /user/queue/notice|errors : 로그인
                   └ 그 밖                      : FORBIDDEN
                  + 세션 구독 수 < max-subscriptions (아니면 RATE_LIMITED)
 SEND /app/**   → 로그인(UNAUTHENTICATED) → DB: 동의(CONSENT_REQUIRED)·차단(BANNED) → 사용자별 초당 한도(RATE_LIMITED)
 SEND 그 밖     → FORBIDDEN
   거절 = 예외 → StompErrorHandler → ERROR 프레임 message=<코드>

/app/cursor {pageId,x,y} → CursorController
   pageId ≠ 현재 → /user/queue/errors {code: PAGE_ENDED}   (연결 유지)
   x,y 캔버스 밖  → /user/queue/errors {code: VALIDATION_FAILED}
   청크 c = (y/480)*16 + x/480 ; 이전 청크 ≠ c → /topic/page.p.chunk.이전 {cursor:leave}
   /topic/page.p.chunk.c {cursor, sid, nickname, color, x, y}
Disconnect → 마지막 청크에 cursor:leave

큐 스레드 (커밋 후 이벤트, 동기) ─▶ PageEventRelay ─▶ SimpBroadcaster ─▶ SimpleBroker (비동기 전송)
   PageStatusChanged → /topic/page.p.meta page:status        (LivePage 캐시 비움)
   OccupancyChanged  → /topic/page.p.meta minimap:diff
   PageTurned        → /topic/page.old.meta page:turned {newPageId}

프론트 (INV-09)
 connection 연결됨 ─┬─▶ metaSync: SUBSCRIBE meta → GET minimap → 버퍼 재생
                   └─▶ chunkSync.resync(): 보이는 청크 전부 다시 구독·조회
 뷰포트 변경 → chunkSync.setVisible(chunks)
    새 청크: SUBSCRIBE(버퍼링) → GET doodles?chunks=새것들 → 청크 내용 교체 → 버퍼 재생 → live
    빠진 청크: UNSUBSCRIBE → doodle·커서 해제
 page:turned / PAGE_ENDED → /api/pages/current 다시 → 새 페이지 전체 로딩
```

| 파일 (예정) | 역할 |
|---|---|
| `backend/build.gradle.kts` | `spring-boot-starter-websocket` |
| `backend/.../realtime/WebSocketConfig.java` | 엔드포인트, 브로커, 인터셉터, 크기 상한 |
| `backend/.../realtime/RealtimeDestinations.java` | 목적지 파싱 (순수) |
| `backend/.../realtime/RealtimeInterceptor.java` | SEC-07/08/15 검증 |
| `backend/.../realtime/SendRateLimiter.java` | 사용자별 초당 한도 (순수) |
| `backend/.../realtime/StompErrorHandler.java` | 에러 코드 → ERROR 프레임 |
| `backend/.../realtime/SubscribeReceipts.java` | SimpleBroker가 구독을 등록한 **뒤** RECEIPT 전송 (SimpleBroker는 RECEIPT를 지원하지 않음, INV-09에 필요) |
| `backend/.../realtime/HeartbeatSchedulerConfig.java` | 브로커 하트비트용 스케줄러 |
| `backend/.../auth/AccessCheck.java` | `작성자` 판정을 REST(`@CurrentUser`)와 STOMP가 같이 씀 |
| `backend/.../realtime/Broadcaster.java`, `SimpBroadcaster.java` | 전송 |
| `backend/.../realtime/PageEventRelay.java` | 페이지 이벤트 → 메타 토픽 |
| `backend/.../realtime/CursorController.java` | 커서 중계, 연결 종료 시 leave |
| `backend/.../page/LivePage.java` | 현재 페이지 캐시 (이벤트로 비움) |
| `frontend/src/realtime/connection.ts` | STOMP 연결 |
| `frontend/src/canvas/chunkSync.ts`, `doodleStore.ts` | 청크 동기화 (`chunkLoader.ts` 대체) |
| `frontend/src/realtime/metaSync.ts` | 메타 동기화 |
| `frontend/src/canvas/cursors.ts`, `CursorLayer.tsx` | 커서 상태, 그리기 |
| `frontend/src/realtime/throttle.ts` | 커서 전송 간격 |
| `frontend/src/realtime/fakeLink.ts` | 테스트용 가짜 연결 |
| `backend/src/test/.../page/FreshPage.java` | 테스트 지원: 컨텍스트가 여러 개라 DB 기준으로 복구한 뒤 새 페이지 시작 |

- 설정값: `graffiti.realtime.cursor-interval` = 100ms, `graffiti.realtime.heartbeat` = 10s 추가(INV-10), config에 `cursorIntervalMs`, `heartbeatMs`. 나머지는 기존 `graffiti.realtime.*`
- 브로커 전송 순서는 `setPreservePublishOrder(true)`로 보장(같은 연결에 이벤트가 순서대로 감). `setPreserveReceiveOrder`는 ERROR 프레임을 막아 쓰지 않음
- 프론트 SUBSCRIBE는 모두 `receipt` 헤더를 달고, 청크 묶음의 RECEIPT가 다 온 뒤 REST로 조회한다

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 핸드셰이크 | 다른 Origin | 403, 연결 안 됨 | `RealtimeIT` |
| SUBSCRIBE | 끝난 페이지, 청크 144 이상, 숫자 아님, `/topic/foo` | ERROR `PAGE_ENDED`/`FORBIDDEN` | `RealtimeDestinationsTest`, `RealtimeIT` |
| SUBSCRIBE | 61번째 구독 | ERROR `RATE_LIMITED` | `RealtimeIT` |
| SUBSCRIBE | 비로그인이 `/user/queue/notice` | ERROR `UNAUTHENTICATED` | `RealtimeIT` |
| SEND | 비로그인, 동의 전, 차단 | ERROR `UNAUTHENTICATED`/`CONSENT_REQUIRED`/`BANNED` | `RealtimeIT` |
| SEND | `/topic/...`에 직접 SEND | ERROR `FORBIDDEN`, 다른 구독자에게 전달 안 됨 | `RealtimeIT` |
| SEND | 같은 사용자 1초에 21번째 | ERROR `RATE_LIMITED` | `SendRateLimiterTest`, `RealtimeIT` |
| SEND | 64KB 초과 프레임 | Spring이 연결을 닫음(프레임 크기 초과) | `RealtimeIT` |
| 커서 | 끝난 `pageId`, 캔버스 밖 좌표 | `/user/queue/errors`에 코드, 연결 유지, 브로드캐스트 없음 | `RealtimeIT` |
| 커서 | 청크 이동 / 연결 끊김 | 이전 청크에 `cursor:leave` | `RealtimeIT` |
| 메타 | 점유율 변경, 상태 변경, 넘김 | `minimap:diff`, `page:status`, `page:turned` | `RealtimeIT` |
| 검증 | 넘김 직후 새 페이지 구독이 캐시보다 먼저 옴 | `LivePage`가 캐시와 다르면 큐로 다시 확인 → 허용 | `LivePageTest` |
| 프론트 | 구독 후 조회 응답 전에 `doodle:published`/`removed` 도착 | 응답 적용 뒤 버퍼 재생 → 최종 상태가 맞음(중복 없음, 삭제된 것 안 보임) | `chunkSync.test` |
| 프론트 | 연결 끊김 후 재연결 | 보이는 청크 전부 다시 구독·조회, 그 사이 삭제된 doodle은 사라짐 | `chunkSync.test` |
| 프론트 | 조회 실패 | 그 청크 구독 해제, 다음 뷰포트 변경 때 재시도 | `chunkSync.test` |
| 프론트 | 연결 안 된 상태에서 뷰포트 변경 | REST 조회는 하되(03처럼 보임), 연결되면 resync로 구독 | `chunkSync.test` |
| 프론트 | `page:turned` 수신 | `/api/pages/current` 다시 받아 새 페이지 로딩 | `CanvasScreen.test` |
| 프론트 | ERROR 프레임 수신 | `PAGE_ENDED`면 현재 페이지 다시 로딩, 그 밖은 stompjs 재연결 후 resync(사용자 메시지 없음) | `connection.test` |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1 (SEC-03): `Origin: https://evil.example`로 `/ws` 핸드셰이크 → 실패. 허용 Origin은 성공
- [x] AC-2 (SEC-07, INV-04): 비로그인 연결도 현재 페이지 청크·메타 구독 가능. 끝난 페이지, 범위 밖 청크, 알 수 없는 목적지 구독은 ERROR(코드 포함)
- [x] AC-3 (SEC-08, D-30): 한 연결에서 61번째 SUBSCRIBE는 ERROR `RATE_LIMITED`. UNSUBSCRIBE 후에는 다시 가능
- [x] AC-4 (SEC-07, SEC-15): 비로그인·동의 전·차단 사용자의 `/app/cursor`는 ERROR, 브로드캐스트 없음. 차단은 연결 중 DB를 바꿔도 다음 SEND부터 적용
- [x] AC-5 (SEC-08, D-12): 같은 사용자가 1초에 21번 SEND하면 21번째가 ERROR `RATE_LIMITED`
- [x] AC-6: 클라이언트가 `/topic/page.p.chunk.0`에 직접 SEND하면 ERROR `FORBIDDEN`, 그 토픽 구독자는 아무것도 받지 않음
- [x] AC-7 (D-22): 작성자 A가 `/app/cursor {x:500,y:10}` → 청크 1 구독자 B가 `{type:"cursor", sid, nickname, color, x, y}`를 받고, 사용자 ID·이메일은 없다. A가 청크 0으로 옮기면 청크 1에 `cursor:leave`, A 연결이 끊기면 마지막 청크에 `cursor:leave`
- [x] AC-8: 끝난 `pageId` 커서 → A의 `/user/queue/errors`에 `PAGE_ENDED`, 연결 유지
- [x] AC-9: 점유율 변화 → 메타 구독자에게 `minimap:diff {set, clear, occupancyPermille}`, 카운트다운 시작 → `page:status`, 넘김 → 옛 페이지 메타에 `page:turned {newPageId}`
- [x] AC-10 (INV-09): 프론트 `chunkSync`는 SUBSCRIBE를 REST 조회보다 먼저 하고, 조회 중 받은 `published`/`removed`를 응답 뒤에 재생한다. 조회 응답에 이미 있는 doodle의 `published`는 중복되지 않고, 대기 중 `removed`된 doodle은 응답에 있어도 보이지 않는다
- [x] AC-11: 재연결 시 보이는 청크를 다시 구독·조회하고 응답에 없는 doodle은 지운다(다른 live 청크 이벤트로 다시 오는 것은 유지)
- [x] AC-12 (INV-10): 프론트 커서 전송 간격은 `config.cursorIntervalMs`, 구독 상한은 서버가 강제(클라이언트는 `maxChunks` 이하만 구독)
- [x] AC-13: 브라우저 두 개(작성자 1, 비로그인 1)에서 작성자의 커서가 다른 창에 닉네임과 함께 보이고, 창을 닫으면 사라진다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | 목적지 파싱, 속도 제한(가짜 `Clock`), `LivePage` 캐시 | `RealtimeDestinationsTest`, `SendRateLimiterTest`, `LivePageTest` |
| 통합 | 실제 포트 + `WebSocketStompClient` 다중 클라이언트: Origin, 구독 검증, 구독 상한, SEND 권한, 속도 제한, `/topic` 직접 SEND, 커서 중계·leave, 메타 이벤트 | `realtime/RealtimeIT` |
| 단위(프론트) | `chunkSync` 순서·버퍼·재연결·실패, `metaSync`, 커서 상태 | `chunkSync.test.ts`, `metaSync.test.ts`, `cursors.test.ts` |
| 컴포넌트(프론트) | `page:turned` 뒤 새 페이지 로딩, 내 커서 숨김과 커서 전송 | `CanvasScreen.test.tsx` |
| 단위(프론트) | 연결 래퍼(receipt, PAGE_ENDED, 하트비트), 전송 간격 | `connection.test.ts`, `throttle.test.ts` |
| QA | 헤드리스 Chromium 두 개(로그인 세션 1, 비로그인 1)로 커서 표시와 사라짐, SQL로 doodle 상태를 바꾼 뒤 재연결 시 반영 | 수동 체크리스트 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 큐 스레드에서 실행되는 리스너(`PageEventRelay`, `LivePage`)는 큐에 커맨드를 내고 기다리면 안 된다(교착). 전송과 캐시 비우기만
- 브로드캐스트 본문에 사용자 ID, 이메일을 넣지 않는다. 로그에 STOMP 세션 ID를 남기지 않는다(SEC-13)
- 인터셉터의 거절은 REST와 같은 `ErrorCode` 이름을 쓴다
- 커서는 저장하지 않는다(세션 속성의 마지막 청크 번호만, INV-06)

## 10. 롤백
PR revert. DB 변경 없음. 프론트는 03의 REST 전용 로딩으로 돌아간다.

## 결정 필요 (승인 시 함께 확정)
1. `@MessageMapping` 처리 중 검증 실패(끝난 `pageId`, 범위 밖 좌표)를 어떻게 알리나 — **추천: 그 연결의 `/user/queue/errors`로 `{type:"error", code}`를 보내고 연결은 유지.** ERROR 프레임은 STOMP 규약상 연결 종료 신호라, 페이지가 넘어가는 순간 날아간 커서 하나로 연결이 끊기면 안 된다. 인터셉터 단계 거절(권한, 구독 검증, 속도)은 API.md대로 ERROR 프레임
2. 내 커서 이벤트 걸러내기 — **추천: 닉네임으로 거름.** 닉네임은 DB 유니크라 충분하고, `sid`를 알려 주는 별도 메시지가 필요 없다. 같은 계정의 다른 탭 커서도 안 보이는데, 본인이라 문제없음
3. `minimap:diff`에 `occupancyPermille` 추가 — **추천: 추가.** 상태가 바뀌지 않는 점유율 변화도 상단 표시(09)와 미니맵에 바로 반영하려면 필요
4. SUBSCRIBE 빈도 제한 — **추천: 11로 미룸**(범위 제외 참고)

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-22 자체 리뷰 (전체 diff)
  - INV-09: SimpleBroker는 SUBSCRIBE `receipt`를 무시해 "구독 등록 → 조회" 순서를 클라이언트가 알 수 없었다 → `SubscribeReceipts`가 브로커 처리 **뒤** RECEIPT를 보냄. `RealtimeIT`가 영수증을 기다린 뒤 이벤트를 받는 것으로 확인
  - 이벤트 순서: 브로커 전송이 스레드 풀이라 `minimap:diff` 둘이 뒤바뀔 수 있었다 → `setPreservePublishOrder(true)`. `setPreserveReceiveOrder`는 ERROR 프레임이 나가지 않아 제외
  - `chunkSync`: 영수증을 기다리는 동안 화면을 벗어나거나 재연결로 상태가 바뀐 청크는 그 묶음의 조회에서 뺀다. 연결 없이 조회한 청크는 조회 중 연결되면 바로 구독 → 조회
  - 큐 스레드 리스너(`PageEventRelay`, `LivePage`)는 큐를 기다리지 않음(전송과 세대 증가만)
  - 커서·이벤트 본문에 사용자 ID·이메일 없음(`RealtimeIT`가 키 집합을 정확히 비교). 로그에 세션 ID 없음. 프론트 `console` 출력 없음
  - 테스트 안정성: Spring 컨텍스트 여러 개가 DB를 공유해, 다른 컨텍스트가 넘긴 페이지를 메모리는 ACTIVE로 알고 있어 전체 실행 때 `RealtimeIT` 준비가 실패했다 → `FreshPage`가 DB 기준으로 복구한 뒤 새 페이지를 시작(`PageCommandProcessorIT`, `CanvasReadIT`, `RealtimeIT`)
  - 결과: 백엔드 `./gradlew test` 전체 통과, 프론트 tsc·eslint·vitest(55개)·build 통과

## QA 기록 (/gf-qa가 추가)
- 2026-10-22 로컬 스택(MySQL 8.4 컨테이너, 04 백엔드 + 테스트 로그인 엔드포인트, Vite) + 헤드리스 Chromium 컨텍스트 두 개(작성자 1, 비로그인 1)
  - 작성자 창에서 포인터를 움직이면 비로그인 창에 파란 점과 "익명의 QA #1" 라벨이 보임. 작성자 창에는 자기 커서가 그려지지 않음
  - 작성자 창을 닫으면 1.5초 안에 비로그인 창에서 커서가 사라짐(연결 종료 → `cursor:leave`)
  - 기존 doodle, 미니맵, 확대·이동은 03과 같게 동작. 콘솔 에러는 favicon 404뿐
  - doodle 게시·삭제 이벤트의 실제 발행은 06/08에서 확인(04는 가짜 이벤트로 병합 단위 테스트)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)
- `realtime/` 변경이라 배포 전 `/gf-security` 대상(PIPELINE §2). 스테이징 배포가 아직 없으므로 11-security-pass에서 함께 감사

## 배포 기록 (/gf-deploy-check가 추가)
