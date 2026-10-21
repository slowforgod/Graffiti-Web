# 02-page-core: 페이지 상태 큐, 점유율 계산, 페이지 넘김 타이머, 재시작 복구

- **상태**: MERGED (PR #16, 2026-10-21)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-21 (결정 필요 1, 2는 추천안으로 확정)
- **브랜치**: `feat/02-page-core`
- **선행 명세**: 00

## 1. 왜 (Why)
서비스의 핵심 규칙(점유율 70% → 6시간 뒤 넘김, 최대 7일)은 여러 사용자가 동시에 게시·삭제하는 중에도 틀리면 안 된다. 이 명세는 그 규칙을 **단일 스레드 큐**(D-03) 뒤에 두고, 순수 로직(`OccupancyGrid`, `PageLifecycle`)을 촘촘히 테스트해서 03~09가 올라설 바닥을 만든다. 백엔드 전용이며 REST, STOMP는 없다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-03 | 페이지 상태 변경은 `PageCommandProcessor` 인터페이스 뒤 단일 스레드 큐 |
| 결정 | D-06 | 칸 48px(160×90), 청크 480px. 생성 시점 설정을 `page` 행에 스냅샷 |
| 결정 | D-14 | 점유율 = 선분 bbox(굵기 포함) 칸 참조 카운트, 텍스트는 박스 bbox |
| 결정 | D-15 | `ACTIVE → COUNTDOWN → ENDED`, 70% 도달 시 6시간, 최대 7일, 낙서 없어도 7일에 넘김 |
| 결정 | D-25 | 시작 시 PUBLISHED doodle로 재계산, `scheduled_end_at`으로 타이머 재설정, 지났으면 즉시 넘김 |
| 결정 | D-17 | 종료 페이지 데이터 보관(`final_occupancy_*`) |
| 결정 | D-32 (PROPOSED) | `page` 순수 로직 라인 커버리지 80% 목표로 테스트 (강제는 D-32 확정 후) |
| 불변 조건 | INV-01 | 점유율·페이지 상태 변경은 큐 커맨드로만 |
| 불변 조건 | INV-02 | 큐 작업은 수 ms. 재계산용 대량 조회는 큐 밖(시작 시)에서 |
| 불변 조건 | INV-03 | 한 방향 전이, 카운트다운 취소 없음, `min(생성+7일, 도달+6시간)`, 이전 상태 조건 UPDATE |
| 불변 조건 | INV-04 | 커맨드는 `pageId`를 받고 현재 페이지가 아니면 `PAGE_ENDED` |
| 불변 조건 | INV-07 | page 행 삭제 없음 |
| 불변 조건 | INV-10 | 모든 수치는 `GraffitiProperties`, 생성 후에는 page 행 스냅샷 값 사용 |
| 불변 조건 | INV-11 | 비종료 페이지 최대 1개 (`live_flag` 유니크) |
| 불변 조건 | INV-12 | `cellCount`는 언제든 PUBLISHED doodle에서 재계산 가능 |
| 보안 | SEC-01 | 상태 전이는 파라미터 바인딩 + 이전 상태 조건 UPDATE, 동시 전이 시 하나만 성공 |
| DB | `DB.md` §2 `page`, §4 상태 전이 쿼리 | `page` 테이블 마이그레이션 추가 |
| 흐름 | `ARCHITECTURE.md` §4.4, §4.5 | 넘김, 재시작 복구 |

## 3. 범위
**포함**
- `page/` 순수 로직 (Spring 의존 없음)
  - `OccupancyGrid`: 칸 참조 카운트 배열, `apply(Footprint, +1|-1)` → 바뀐 칸(0↔1) 목록, `occupancyPermille()`, `bitmap()`(160×90 비트, 행 우선)
  - `Footprint`: 그림(획 목록: 굵기, 점 배열)과 텍스트(bbox)를 칸 집합으로 변환. 선분 bbox를 굵기의 절반만큼 확장, 캔버스 밖은 잘라냄. 점 1개짜리 획도 처리
  - `PageLifecycle`: 상태 + 시각 + 점유율 → 다음 상태와 `scheduledEndAt`, 종료 사유(`OCCUPANCY`/`MAX_LIFETIME`) 계산
- `page/` 큐와 저장
  - `PageCommandProcessor` 인터페이스 + `SingleThreadPageCommandProcessor`: 커맨드를 단일 스레드에서 순서대로 실행, 결과를 `CompletableFuture`로 반환. 커맨드마다 DB 트랜잭션 하나, **커밋 성공 뒤에** 메모리 상태 반영과 이벤트 발행 (`ARCHITECTURE.md` §4.1 마지막 문단)
  - 이 명세의 커맨드: `ApplyFootprint(pageId, footprint, delta)`(06/08/10이 사용할 점유율 갱신 진입점), `EndPage(pageId)`(타이머), `GetPageSnapshot()`(읽기)
  - `PageState`: 현재 페이지의 메모리 상태 (`OccupancyGrid`, 상태, 시각들). 큐 스레드만 변경
  - `Page` 엔티티 + 저장소. 전이는 `UPDATE ... WHERE id=? AND status=?`, 영향 0행이면 예외
  - `PageTimer`: Spring `TaskScheduler`로 `scheduledEndAt`에 `EndPage` 예약. 카운트다운 시작으로 종료 시각이 당겨지면 재예약
  - `PageBootstrap`: 시작 시 비종료 페이지 조회(없으면 생성) → 점유율 재계산 → 지났으면 즉시 `EndPage`, 아니면 예약. **웹 서버가 요청을 받기 전에** 끝남(`SmartLifecycle` phase를 웹 서버보다 앞에)
  - 이벤트: `PageStatusChanged`, `OccupancyChanged(set, clear)`, `PageTurned(oldId, newId)`를 Spring `ApplicationEvent`로 발행 (04-realtime이 STOMP로 중계)
- DB: `V3__page.sql` (DB.md DDL 그대로, `live_flag` 생성 열과 유니크 포함)
- 문서: `ARCHITECTURE.md` §2 `page/` 목록과 §4.4/4.5에 커맨드 이름, 이벤트 이름 반영

**제외 (NOT in scope)**
- `doodle` 테이블과 게시·삭제 커맨드 → 06, 08. 02에서는 재시작 복구가 쓸 `PublishedFootprintSource` 인터페이스만 두고, 구현은 "doodle 없음"(빈 목록)으로 시작. 06이 `doodle` 테이블과 함께 실제 구현으로 교체
- 쿨다운(`ReservePost`) → 06
- `/api/pages/current`, 미니맵 API → 03
- STOMP 브로드캐스트 → 04 (02는 Spring 이벤트까지)
- 다중 서버 큐 → TODOS(이미 있음)

## 4. 현재 상태 (검증된 사실)
- 00-bootstrap(PR #3) 기준: `GraffitiProperties`에 `canvas.*`, `page.*` 키와 검증(청크·칸 정렬) 있음. `Clock` 빈 있음. `ErrorCode.PAGE_ENDED` 있음. 테이블 없음

## 5. 설계

```
                    (큐 밖)                                   (큐 안: 단일 스레드)
 06 게시/08 삭제 ── submit(ApplyFootprint) ─▶ ┌──────────────────────────────────────────┐
 PageTimer ──────── submit(EndPage) ────────▶ │ 1. pageId == 현재 페이지? 아니면 PAGE_ENDED  │
 03 조회 ─────────── submit(GetPageSnapshot) ▶ │ 2. 새 상태를 복사본에서 계산(순수 로직)        │
                                              │ 3. TX: UPDATE page ... WHERE status=이전    │
                                              │        (EndPage면 INSERT 새 page까지)        │
                                              │ 4. 커밋 성공 → PageState 교체               │
                                              │ 5. 이벤트 발행 (커밋 뒤)                     │
                                              │ 실패 → 메모리 그대로, future 예외 완료       │
                                              └──────────────────────────────────────────┘
ApplyFootprint: grid.apply → 점유율 ≥ 스냅샷 기준 && ACTIVE면
                UPDATE status=COUNTDOWN, countdown_started_at, scheduled_end_at=min(...)  → PageTimer 재예약
                (점유율 변화만 있으면 DB 쓰기 없음: 점유율은 메모리 + 재계산 가능, INV-12)
EndPage:        UPDATE status=ENDED, ended_at, end_reason, final_occupancy_* WHERE status IN 이전 상태
                INSERT page(ACTIVE, seq+1, 현재 설정 스냅샷)  → 같은 TX
                → PageTurned 이벤트, 새 페이지 타이머 예약

시작 (웹 서버 시작 전)
 PageBootstrap: 비종료 page 조회 ─ 없으면 생성
                └ PublishedFootprintSource로 재계산 (큐 밖, 시작 시 1회 — INV-02)
                └ scheduled_end_at ≤ now → EndPage 즉시 / 아니면 예약
```

| 파일 (예정) | 역할 |
|---|---|
| `page/OccupancyGrid.java`, `Footprint.java`, `PageLifecycle.java` | 순수 로직 |
| `page/PageCommandProcessor.java`, `SingleThreadPageCommandProcessor.java`, `PageCommand.java` | 큐 |
| `page/PageState.java` | 메모리 상태 |
| `page/Page.java`, `PageStatus.java`, `PageEndReason.java`, `PageRepository.java` | 저장 |
| `page/PageTimer.java`, `PageBootstrap.java`, `PublishedFootprintSource.java` | 타이머, 복구 |
| `page/event/*.java` | 이벤트 레코드 |
| `db/migration/V3__page.sql` | 스키마 |

- 설정값: 새 키 없음. 페이지 생성 시 `graffiti.canvas.*`, `graffiti.page.*`를 page 행에 스냅샷하고, 그 페이지가 끝날 때까지 행의 값을 쓴다(D-06)
- 마이그레이션 번호: 01과 02가 병렬이라 둘 다 `V1`을 쓰면 충돌한다. **추천: 01은 `V1__users.sql`, `V2__spring_session.sql`, 02는 `V3__page.sql`.** 순서가 어긋나는 문제는 이미 적용된 DB(스테이징)에서만 생기는데, 스테이징 DB는 12a 이후에 생기므로 M1 안에서는 머지 순서와 무관하다

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (결과) | 테스트 |
|---|---|---|---|
| 큐 | 커맨드 실행 중 DB 오류 | 트랜잭션 롤백, 메모리·이벤트 변화 없음, future 예외 완료(삼키지 않음), 큐 스레드는 계속 동작 | `PageCommandProcessorIT` (DB 실패 주입) |
| 큐 | 커맨드가 예외를 던짐 | 다음 커맨드 정상 처리 | 같은 IT |
| 큐 | 여러 스레드가 동시에 `ApplyFootprint` | 순서대로 처리, 최종 카운트가 순차 결과와 같음 | 동시성 IT (스레드 50개) |
| 전이 | 70%를 넘는 두 커맨드가 연속 | 카운트다운 1번만 시작, `countdown_started_at` 재기록 없음 | `PageLifecycleTest`, IT |
| 전이 | 카운트다운 뒤 점유율이 70% 아래로 | 상태 유지(취소 없음) | `PageLifecycleTest` |
| 전이 | 같은 페이지에 `EndPage` 두 번(타이머 중복) | 두 번째는 현재 페이지가 아니므로 무시(PAGE_ENDED 결과, 로그만) | IT |
| 전이 | 다른 경로가 DB 상태를 바꿔 조건 UPDATE가 0행 | 예외, 메모리 그대로 | IT |
| 전이 | 비종료 페이지를 두 개 만들려 함 | `uk_page_live` 위반으로 실패 (INV-11) | IT |
| 넘김 | 넘김 직전에 들어온 옛 pageId 커맨드 | `PAGE_ENDED` | IT |
| 복구 | 서버가 꺼져 있는 동안 `scheduled_end_at`이 지남 | 시작 시 즉시 넘김, 새 페이지 생성 | `PageBootstrapIT` (`Clock` 조작) |
| 복구 | 첫 기동(page 없음) | 새 페이지(seq 1) 생성 | `PageBootstrapIT` |
| 타이머 | 카운트다운 시작으로 종료 시각이 당겨짐 | 기존 예약 취소, 새 시각 예약 | `PageTimerTest` (가짜 `TaskScheduler`) |
| 점유율 | 캔버스 가장자리/밖 좌표, 점 1개 획, 굵은 선 | 캔버스 안 칸만, 경계 칸 포함 | `FootprintTest` 표 기반 |
| 점유율 | 같은 칸을 여러 doodle이 덮고 하나만 삭제 | 카운트 > 0이면 점유 유지 | `OccupancyGridTest` |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [ ] AC-1 (D-14): 선분 (100,100)→(200,100) 굵기 8의 칸 집합 = x 96~204, y 96~104 bbox가 덮는 칸(칸 48px 기준 열 2~4, 행 2) — 표 기반 테스트로 굵기·대각선·경계 케이스 포함
- [x] AC-2: 텍스트 bbox (0,0,48,48)은 칸 1개, (47,47,2,2)는 칸 4개
- [ ] AC-3 (INV-12): 같은 footprint를 +1 후 -1 하면 그리드가 처음과 같다. 임의 순서의 footprint 집합을 적용한 결과가 순서와 무관하다
- [x] AC-4: `occupancyPermille` = 점유 칸 수 × 1000 / 14,400 (내림), `bitmap`은 1,800바이트, 칸 (col,row)의 비트 = `row*160+col`
- [ ] AC-5 (D-15): 점유율이 700‰에 도달한 커맨드에서 상태가 COUNTDOWN, `countdown_started_at = now`, `scheduled_end_at = min(created+7d, now+6h)`
- [ ] AC-6 (INV-03): COUNTDOWN에서 점유율이 내려가도 COUNTDOWN 유지, `countdown_started_at` 불변
- [x] AC-7: 7일이 지나면 낙서가 없어도 `end_reason=MAX_LIFETIME`으로 종료되고 seq+1 페이지가 ACTIVE로 생성된다
- [x] AC-8: 카운트다운 종료 시 `end_reason=OCCUPANCY`, `final_occupancy_permille`, `final_occupancy_bitmap` 저장
- [ ] AC-9 (INV-04): 끝난 pageId로 `ApplyFootprint` → `PAGE_ENDED`, 상태 변화 없음
- [ ] AC-10 (INV-11, SEC-01): 동시에 두 전이를 시도하면 하나만 성공 / 비종료 페이지 2개 INSERT가 DB에서 거절됨
- [ ] AC-11 (D-25): 재시작 시 PUBLISHED footprint로 그리드가 재계산되고, 지난 `scheduled_end_at`은 즉시 넘김, 아니면 예약된다. 이 과정이 웹 요청 수신 전에 끝난다
- [x] AC-12: DB 실패 시 메모리 상태와 이벤트가 바뀌지 않고, 이후 커맨드가 정상 처리된다
- [x] AC-13: 이벤트(`PageStatusChanged`, `OccupancyChanged`, `PageTurned`)는 커밋 후에만 발행된다
- [ ] AC-14 (INV-10): `page` 코드에 7680, 4320, 48, 480, 0.7, 6h, 7d 같은 숫자가 없다(테스트 fixture 제외)
- [ ] AC-15 (INV-07): `page` 행 DELETE 경로가 없다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | `Footprint`, `OccupancyGrid`, `PageLifecycle` (표 기반, 경계값) | `FootprintTest`, `OccupancyGridTest`, `PageLifecycleTest` |
| 단위 | 타이머 예약/재예약 | `PageTimerTest` (가짜 `TaskScheduler`, 고정 `Clock`) |
| 통합 | 큐 + MySQL: 전이 조건 UPDATE, INV-11, 동시성, DB 실패, 이벤트 시점 | `PageCommandProcessorIT` |
| 통합 | 시작 복구 | `PageBootstrapIT` |
| QA | 로컬에서 설정값을 짧게(`countdown-duration: 1m`, `max-lifetime: 2m`) 바꿔 실제 넘김이 일어나고 재시작 복구가 되는지 확인 | 수동 체크리스트 |

- 커버리지: `page` 순수 로직 라인 80% 이상을 목표로 하고 JaCoCo 리포트를 QA 기록에 남긴다(D-32 PROPOSED라 빌드 실패 조건은 아님)

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 큐 밖에서 `PageState`나 `page.status`를 바꾸는 코드 금지(INV-01). 큐 작업 안에서 대량 조회·외부 호출·sleep 금지(INV-02)
- 커밋 전에 메모리를 바꾸거나 이벤트를 발행하지 않는다
- 현재 시각은 `Clock`으로만. 테스트는 고정/조작 가능한 `Clock` 사용
- `PublishedFootprintSource`의 빈 구현에는 "06에서 교체" 주석만 두고 가짜 데이터를 넣지 않는다

## 10. 롤백
PR revert. `V3__page.sql`은 새 테이블만 추가. 배포 전이므로 데이터 이전 없음.

## 결정 필요 (승인 시 함께 확정)
1. 마이그레이션 번호 — 추천: 01 `V1__users.sql` + `V2__spring_session.sql`, 02 `V3__page.sql`
2. `doodle` 테이블 소유 — 추천: 06-publish가 만들고, 02는 복구용 인터페이스만 (doodle 스키마와 게시 흐름을 한 PR에서 검토하기 위해)

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-21 · `4e50ee4` · CRITICAL 0 / INFO 3 · AUTO-FIX 2 · ASK 없음 · PASS
  - AUTO-FIX 1 (경쟁 조건, 테스트가 잡음): 시작 시 이미 종료 시각이 지난 페이지는 `InstallPage`가 건 타이머와 `PageBootstrap`의 `EndPage`가 동시에 넘기려 해서, 타이머가 먼저 실행되면 기동이 `PAGE_ENDED`로 실패했음. 둘 다 큐에서 순서대로 실행되므로 두 번째의 `PAGE_ENDED`를 "이미 넘어감"으로 처리
  - AUTO-FIX 2: 커맨드 시각을 밀리초로 잘라 DB `DATETIME(3)`과 메모리 값을 맞춤
  - INFO-1: `@SuppressWarnings` 없이 타입 안전하게 디스패치하려고 `PageCommand.executeOn(handler)` 방식 사용 (Gate Integrity RH-13 회피)
  - INFO-2: 점유율 변화만 있는 `ApplyFootprint`는 DB 쓰기가 없음(INV-12, 재계산 가능). 06에서 doodle INSERT와 같은 트랜잭션으로 묶을 예정
  - INFO-3: 큐 스레드 종료 시 남은 커맨드는 실행하고 새 제출은 거절(`shutdown()`)

## QA 기록 (/gf-qa가 추가)
- 2026-10-21 · `4e50ee4` · 통과 · 버그 1(위 AUTO-FIX 1, 회귀 테스트 `PageBootstrapIT.restartAfterTheScheduledEndTurnsThePageAtOnce`) · PASS
  - 자동: backend `./gradlew test` 70개 통과(실패 0, 스킵 0). `page` 패키지 라인 커버리지: 순수 로직 `OccupancyGrid` 100%, `PageLifecycle` 100%, `Footprint` 93%, `CellGeometry` 85%, 큐 `PageCommandHandler` 98%
  - 수동: 로컬 MySQL + `bootRun`(`GRAFFITI_PAGE_MAXLIFETIME=30s`) → 페이지 1, 2가 30초마다 `MAX_LIFETIME`으로 넘어가고 seq가 이어짐, `final_occupancy_bitmap` 1,800바이트 저장 / 서버를 끄고 종료 시각이 지난 뒤 재시작 → 로그상 Tomcat 시작 전에 페이지 3이 즉시 넘어가고 페이지 4가 ACTIVE
  - 미검증: 실제 낙서로 70% 카운트다운을 일으키는 시나리오는 06(게시) 이후. 지금은 통합 테스트(`PageCommandProcessorIT`)로만 확인

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
