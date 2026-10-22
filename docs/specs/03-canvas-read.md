# 03-canvas-read: 현재 페이지, 청크 조회, 미니맵 API와 Konva 뷰포트

- **상태**: IN_PROGRESS (PR 대기)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-22 (standing 지시 "승인 받지말고 다 해"에 따라 결정 필요 1~3은 추천안으로 확정)
- **브랜치**: `feat/03-canvas-read`
- **선행 명세**: 01, 02

## 1. 왜 (Why)
지금은 로그인만 되고 캔버스가 없다. 이 명세는 누구나(비로그인 포함) 현재 페이지를 열어 이동·확대하며 게시된 낙서와 미니맵을 볼 수 있게 한다. 04(실시간)는 이 조회 경로 위에 "구독 → 조회 → 병합"을 얹고, 05·06은 이 뷰포트 위에서 그린다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-05 | 캔버스 7680×4320, 줌 0.5~4배. 클라이언트는 서버가 준 값으로 제한 |
| 결정 | D-06 | 청크 480px(16×9=144개), 칸 48px. 페이지 행 스냅샷 값을 응답 |
| 결정 | D-07 | 배경 흰색 + 옅은 격자, 팔레트·굵기·글자 크기 목록을 config로 전달. PC는 스페이스+드래그 이동, 휠 줌. 두 손가락은 이동과 줌 |
| 결정 | D-08 | 미니맵 = 160×90 점유율 비트맵 + 현재 보는 영역 사각형 |
| 결정 | D-09 | 보이는 청크만 조회. (구독은 04, 03은 조회만) |
| 결정 | D-17 | 종료된 페이지는 열람하지 않음 → `PAGE_ENDED` |
| 결정 | D-22 | 공개 응답에 작성자 정보 없음 |
| 결정 | D-24 | `doodle_chunk`로 청크 조회 |
| 결정 | D-30 (PROPOSED) | 청크 조회 최대 60개 |
| 불변 조건 | INV-01 | 읽기만 한다. 미니맵 비트맵은 큐 커맨드(`GetMinimap`)로 읽어 큐 밖에서 그리드를 만지지 않음 |
| 불변 조건 | INV-04 | 조회도 `pageId`가 현재 페이지가 아니면 `PAGE_ENDED` |
| 불변 조건 | INV-07 | 조회는 `status='PUBLISHED'`만, 행 삭제 없음 |
| 불변 조건 | INV-10 | 클라이언트 수치는 `/api/pages/current`의 `config`에서만. 프론트에 7680, 480, 0.5 같은 숫자 없음 |
| 보안 | SEC-01 | 청크 `IN` 목록도 파라미터 바인딩 |
| 보안 | SEC-02 | `chunks` 파라미터 서버 재검증(정수, 범위, 개수) |
| 보안 | SEC-09 | 공개 조회. 청크 61개 이상은 400, IP 속도 제한은 nginx(12a에서 `/api/` 20r/s) |
| 보안 | SEC-12 | 텍스트 doodle은 Konva `Text`로만 그림 |
| 보안 | SEC-13 | 응답에 `author_id`, 닉네임, 이메일 없음 |
| API | `API.md` §2 | 3개 엔드포인트 구현(형식 그대로), 공개 권한 |
| DB | `DB.md` §2 `doodle`, `doodle_chunk`, §4 청크 조회 | `V4__doodle.sql`로 두 테이블 생성 (결정 필요 1) |

## 3. 범위
**포함**
- 백엔드
  - `V4__doodle.sql`: `DB.md` §2의 `doodle`, `doodle_chunk` DDL 그대로
  - `doodle/ChunkDoodleQuery`: `DB.md` §4 청크 조회 쿼리(JDBC, 파라미터 바인딩). payload는 저장된 JSON을 그대로 응답에 넣음
  - `doodle/ChunkList`(순수 로직): `chunks` 문자열 파싱과 검증(정수, `0 ≤ i < 청크 수`, 1~`max-chunks`개, 중복 제거)
  - `page/PageController`: `GET /api/pages/current`, `GET /api/pages/{pageId}/minimap`
  - `doodle/ChunkDoodleController`: `GET /api/pages/{pageId}/doodles?chunks=`
  - `page/PageCommand.GetMinimap(pageId)`: 현재 페이지의 비트맵과 점유율(큐 안, 1,800바이트 복사라 수 μs)
  - `SecurityConfig`: `GET /api/pages/**` 공개
- 프론트엔드
  - `konva`, `react-konva` 추가(버전 고정, SEC-16)
  - `shared/pageConfig.ts`: `/api/pages/current` 타입과 조회
  - `canvas/viewport.ts`(순수): 화면↔월드 좌표, 줌 제한(`zoomMin`~`zoomMax`), 포인터 기준 줌, 캔버스 밖으로 너무 나가지 않게 이동 제한
  - `canvas/chunks.ts`(순수): 뷰포트 → 보이는 청크 집합, bbox → 청크 집합, 60개 초과 시 화면 중심에 가까운 순으로 자름
  - `canvas/doodleStore.ts`(순수): id → doodle 맵, 청크별 소속, 청크를 떠날 때 다른 보이는 청크에 걸치지 않은 doodle 해제
  - `canvas/CanvasView.tsx`: Konva Stage(창 크기), 배경 + 격자(칸 단위, 보이는 범위만), doodle 레이어 하나에 id 순서로 그림(`Line`, 점 1개 획은 `Circle`), 텍스트(`Text`)
  - 조작: 휠 줌(포인터 기준), 드래그 이동(03에는 그리기 도구가 없으므로 항상 이동), 두 손가락 핀치 줌·이동. 스페이스+드래그는 다른 모드가 생기는 05에서
  - 뷰포트가 바뀌면(디바운스) 새로 보이는 청크만 `GET .../doodles?chunks=` 조회, 벗어난 청크의 doodle 해제
  - `ui/Minimap.tsx`: 160×90 비트맵을 `<canvas>`에 그리고 현재 보는 영역 사각형 표시
  - 첫 화면: 캔버스 중앙, 줌 1배. `PAGE_ENDED`를 받으면 `/api/pages/current`를 다시 받아 새 페이지로 다시 로딩
- 문서: `API.md` §2 공개 여부와 `chunks` 검증 규칙, `ARCHITECTURE.md` §2/§3 파일 목록

**제외 (NOT in scope)**
- STOMP 구독, 실시간 병합, 커서 → 04
- 미니맵 실시간 갱신(`minimap:diff`) → 04
- 그리기 도구, 툴바 모드 전환 → 05 (03은 이동 모드 고정)
- doodle INSERT와 게시 → 06 (03은 테이블과 조회만. 테스트는 SQL로 행을 넣어 확인)
- `PublishedFootprintSource`를 실제 doodle에서 읽도록 교체 → 06 (게시가 생길 때 함께)
- 미니맵 클릭으로 이동, 상단 상태바·카운트다운 → 09. 미니맵 클릭 이동은 TODOS에 추가하지 않고 09에서 판단
- 공개 REST 앱 내부 속도 제한 → nginx가 이미 함(12a). 앱 내부 제한은 11에서 재검토

## 4. 현재 상태 (검증된 사실)
- `page/PageCommand.java`: `ApplyFootprint`, `EndPage`, `GetPageSnapshot`, `EnsureLivePage`, `InstallPage`. `PageSnapshot`에 id, seq, status, occupancyPermille, 시각들, `CellGeometry`(width, height, cellSize), chunkSize
- `page/OccupancyGrid.bitmap()`: 행 우선, MSB 먼저 (`API.md` §2 형식과 같음)
- `config/SecurityConfig.java`: `/api/**`는 `GET /api/me`만 공개. 그 외 경로 공개
- `db/migration`: V0~V3. `doodle`, `doodle_chunk` 테이블 없음
- 프론트: `App.tsx`(로그인 바, 동의 화면), `api/client.ts`(`apiFetch`, `ApiError`). Konva 없음

## 5. 설계

```
GET /api/pages/current ─▶ queue: GetPageSnapshot ─▶ { id, seq, status, occupancyPermille, 시각, serverTime,
                                                     config: 스냅샷(width,height,chunkSize,cellSize)
                                                             + GraffitiProperties(zoom, palette, ...) }
GET /api/pages/{p}/minimap ─▶ queue: GetMinimap(p) ─ p ≠ 현재 → PAGE_ENDED
                                   └▶ { cols, rows, bitmap(base64), occupancyPermille }
GET /api/pages/{p}/doodles?chunks=a,b
   ├ ChunkList.parse (청크 수는 현재 페이지 스냅샷 기준) ── 실패 → 400 VALIDATION_FAILED {field: chunks}
   ├ queue: GetPageSnapshot → p ≠ 현재 → 409 PAGE_ENDED
   └ (큐 밖) ChunkDoodleQuery: doodle_chunk ⋈ doodle WHERE page_id=? AND chunk_index IN (?..) AND status='PUBLISHED'
                               → DISTINCT, id 오름차순

프론트
 App ─ usePage(): /api/pages/current → config
  └ CanvasView(config)
      viewport{x,y,scale} ── 변경(디바운스) ─▶ visibleChunks(viewport, 창 크기, config) (≤ maxChunks)
         ├ 새 청크 → GET doodles?chunks=새것들 → doodleStore.addChunk(i, doodles)
         └ 빠진 청크 → doodleStore.dropChunk(i) (다른 보이는 청크에 걸친 doodle은 유지)
      Layer[배경+격자] / Layer[doodle, id 순서: Line·Circle·Text] / (04: 커서 레이어)
  └ Minimap(config, bitmap, viewport)
```

| 파일 (예정) | 역할 |
|---|---|
| `backend/.../db/migration/V4__doodle.sql` | `doodle`, `doodle_chunk` |
| `backend/.../doodle/ChunkList.java` | `chunks` 파라미터 파싱, 검증 (순수) |
| `backend/.../doodle/ChunkDoodleQuery.java` | 청크 조회 SQL |
| `backend/.../doodle/ChunkDoodleController.java` | 청크 조회 API |
| `backend/.../page/PageController.java` | 현재 페이지, 미니맵 API |
| `backend/.../page/PageCommand.java`, `PageCommandHandler.java` | `GetMinimap` 추가 |
| `backend/.../config/SecurityConfig.java` | `GET /api/pages/**` 공개 |
| `frontend/src/shared/pageConfig.ts` | 현재 페이지 타입, 조회 |
| `frontend/src/canvas/viewport.ts`, `chunks.ts`, `doodleStore.ts`, `chunkLoader.ts` | 순수 로직 (보이는 청크 로딩 포함) |
| `frontend/src/canvas/CanvasScreen.tsx`, `CanvasView.tsx`, `DoodleShape.tsx`, `GridBackground.tsx` | 페이지 로딩, Konva 렌더링, 입력 |
| `frontend/src/canvas/doodles.ts` | 청크 조회 API, doodle 타입 |
| `frontend/src/ui/Minimap.tsx`, `minimap.ts` | 비트맵 디코드(순수), 그리기 |

- 설정값: `API.md`의 `config`에 있던 `defaultFontSize`, `countdownWarningSec`이 `GraffitiProperties`에 없어서 `graffiti.doodle.default-font-size`(24, font-sizes 안의 값만 허용), `graffiti.page.countdown-warning`(5m)을 추가한다(INV-10). 응답 `config`는 `ARCHITECTURE.md` §6 값(그리기용 값 포함, 05가 사용)과 `query.max-chunks`(`maxChunks`로 추가)를 내려준다. 캔버스 크기, 청크, 칸은 **현재 페이지 행의 스냅샷 값**(D-06)

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 청크 조회 | `chunks` 빈 값, 숫자 아님, 음수, 144 이상, 61개 이상 | 400 `VALIDATION_FAILED` `details.field=chunks` | `ChunkListTest`, `CanvasReadIT` |
| 청크 조회 | 같은 청크 중복 | 중복 제거 후 조회 | `ChunkListTest` |
| 청크 조회 | 종료된 페이지 ID / 없는 ID | 409 `PAGE_ENDED` | `CanvasReadIT` |
| 청크 조회 | 여러 청크에 걸친 doodle | 한 번만 반환 (DISTINCT) | `CanvasReadIT` |
| 청크 조회 | DELETED/HIDDEN/REJECTED doodle | 반환 안 함 | `CanvasReadIT` |
| 청크 조회 | 조회 중 페이지 넘김 | 옛 페이지 데이터가 한 번 반환될 수 있음. 클라이언트는 다음 요청에서 `PAGE_ENDED`를 받고 새 페이지로 다시 로딩 (04 이후에는 `page:turned`) | `CanvasScreen.test` |
| 미니맵 | 종료된 페이지 | 409 `PAGE_ENDED` | `CanvasReadIT` |
| 공개 | 비로그인 요청 | 200 (세 API 모두) | `CanvasReadIT` |
| 공개 | 응답에 작성자 정보 | 없음 (`author`, `email`, `nickname` 키 없음) | `CanvasReadIT` |
| 프론트 | `/api/pages/current` 실패 | "캔버스를 불러오지 못했어요. 새로고침해 주세요." 안내, 로그인 바는 그대로 | `CanvasScreen.test` |
| 프론트 | 청크 조회 실패(네트워크) | 그 청크는 비워 두고 다음 뷰포트 변경 때 다시 시도 (조회 실패한 청크는 "로딩됨"으로 표시하지 않음) | `chunkLoader.test` |
| 프론트 | 큰 화면에서 보이는 청크가 60개 초과 | 화면 중심에 가까운 60개만 조회 | `chunks.test` |
| 프론트 | 줌/이동으로 캔버스 밖 | 줌은 `zoomMin`~`zoomMax`로 고정, 이동은 캔버스가 화면에서 완전히 사라지지 않게 제한 | `viewport.test` |
| 프론트 | 응답 도착 전 그 청크가 화면에서 벗어남 | 응답 무시(보이지 않는 청크의 doodle을 넣지 않음) | `chunkLoader.test` |

critical gap: 0

## 7. 수용 기준 (테스트 가능하게)
- [x] AC-1: 비로그인 `GET /api/pages/current` → 200, `id`는 문자열, `config.width/height/chunkSize/cellSize`는 현재 페이지 행 값, `serverTime`은 주입된 `Clock` 시각, `config`에 palette 12색·strokeWidths·fontSizes·zoomMin/Max·maxChunks 포함
- [x] AC-2: 현재 페이지에서 칸 몇 개를 점유시킨 뒤 `GET /minimap` → `cols=160, rows=90`, base64 디코드 1,800바이트, 점유 칸 비트만 1, `occupancyPermille`이 스냅샷과 같음
- [x] AC-3: 청크 0과 1에 걸친 PUBLISHED doodle 1개, 청크 1의 DELETED doodle 1개가 있을 때 `chunks=0,1` → doodle 1개, `payload`는 저장한 JSON과 같은 구조, `bbox`, `kind`, `id`(문자열)만 있고 작성자 정보 없음
- [x] AC-4 (SEC-09, D-30): `chunks` 61개 → 400 `VALIDATION_FAILED`, `chunks=144`(범위 밖) → 400, `chunks=a` → 400, `chunks` 없음 → 400
- [x] AC-5 (INV-04, D-17): 페이지를 넘긴 뒤 옛 pageId로 `doodles`, `minimap` → 409 `PAGE_ENDED`
- [x] AC-6 (SEC-01): 청크 조회 SQL은 `NamedParameterJdbcTemplate` 바인딩만 사용 (코드 리뷰 + 테스트에서 `chunks`에 SQL 조각을 넣으면 400)
- [x] AC-7 (D-05, INV-10): `viewport` 줌은 config의 min/max로 고정, 포인터 아래 월드 좌표가 줌 전후로 같다
- [x] AC-8 (D-06): 1920×1080 화면, 줌 0.5에서 보이는 청크 수 ≤ 60. 3840×2160, 0.5배처럼 60개를 넘으면 중심에서 가까운 60개
- [x] AC-9: 청크 3, 4에 걸친 doodle은 청크 3이 화면에서 빠져도 청크 4가 보이면 유지, 둘 다 빠지면 해제
- [x] AC-10 (D-08): 미니맵 비트 순서 디코드가 서버와 같다(`row*cols+col`, MSB 먼저)
- [x] AC-11 (INV-10): `frontend/src`(테스트 제외)에 7680, 4320, 480, 48, 0.5, 4.0, 60 같은 캔버스 수치 리터럴이 없다
- [x] AC-12 (SEC-12): 텍스트 doodle은 Konva `Text`로 그리고 DOM에 사용자 텍스트가 없다 (`dangerouslySetInnerHTML` 린트 규칙 유지)
- [x] AC-13: 브라우저에서 캔버스가 열리고 휠 줌, 드래그 이동, 미니맵의 보는 영역 사각형이 따라 움직인다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | `ChunkList` 파싱·검증 표 | `doodle/ChunkListTest` |
| 통합 | 세 API + MySQL, 공개 접근, PAGE_ENDED, DISTINCT, 상태 필터, 작성자 정보 없음 | `doodle/CanvasReadIT` |
| 단위(프론트) | `viewport`, `chunks`, `doodleStore`, `minimap` 디코드 | `*.test.ts` |
| 단위(프론트) | `ChunkLoader`: 새 청크만 조회, 늦게 온 응답 무시, 실패 재시도, `PAGE_ENDED` 보고 | `canvas/chunkLoader.test.ts` |
| 컴포넌트(프론트) | `CanvasScreen`: 페이지·청크 로딩, `PAGE_ENDED` 뒤 새 페이지, 실패 안내 | `canvas/CanvasScreen.test.tsx` |
| QA | 로컬 MySQL에 SQL로 doodle 몇 개(그림, 텍스트, 여러 청크 걸침) 넣고 Chrome에서 이동·줌·미니맵 확인 | 수동 체크리스트 |

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 큐 안에서 DB 조회 금지(INV-02). 청크 조회 SQL은 컨트롤러 스레드에서
- 큐 밖에서 `OccupancyGrid`를 직접 읽지 않는다. 비트맵은 `GetMinimap`의 복사본
- 응답 DTO에 `authorId`를 넣지 않는다. SELECT에도 `author_id`를 포함하지 않는다
- 프론트 수치는 `config`에서만. 디바운스 같은 UI 타이밍 상수는 캔버스 규칙 수치가 아니므로 모듈 상수로 둔다

## 10. 롤백
PR revert. `V4__doodle.sql`은 빈 테이블 두 개만 만든다. 배포 후 되돌려야 하면 Flyway 기록이 남으므로 revert 대신 다음 마이그레이션에서 처리(06이 같은 테이블을 쓰므로 실제로는 되돌릴 일이 없음).

## 결정 필요 (승인 시 함께 확정)
1. `doodle`, `doodle_chunk` 테이블을 누가 만드나 — 02에서는 "06이 만든다"로 정했지만 청크 조회가 이 테이블을 읽는다. **추천: 03이 `V4__doodle.sql`로 DB.md DDL 그대로 만든다.** 조회 SQL을 실제 MySQL로 테스트할 수 있고, 06은 INSERT와 게시 흐름에 집중한다. `PublishedFootprintSource` 교체는 그대로 06
2. 큰 화면에서 보이는 청크가 60개를 넘을 때 — **추천: 화면 중심에서 가까운 60개만 조회.** 4K 화면 0.5배 같은 드문 경우이고 가장자리만 비어 보인다. 대안(줌 하한을 화면 크기에 맞춰 올리기)은 D-05의 0.5배 약속과 어긋남
3. `config`에 `maxChunks` 추가 — **추천: 추가.** 클라이언트가 60을 하드코딩하지 않도록(INV-10)

---

## 리뷰 기록 (/gf-review가 추가)
- 2026-10-22 · `9476957` · CRITICAL 0 / INFO 3 · AUTO-FIX 1 · ASK 없음 · PASS
  - AUTO-FIX 1 (테스트 중 발견): IT의 doodle INSERT가 `LAST_INSERT_ID()`를 별도 커넥션에서 읽을 수 있어 `SimpleJdbcInsert`의 생성 키로 교체
  - INFO-1: 공개 읽기 세 개가 모두 큐를 한 번 거친다(스냅샷 비교). 큐 작업은 복사 수준이라 수 μs. 공개 조회 폭탄은 nginx `limit_req`(12a)가 먼저 막음. 앱 내부 제한은 11에서 재검토
  - INFO-2: 페이지 확인과 SQL 조회 사이에 페이지가 넘어가면 옛 페이지 doodle이 한 번 응답될 수 있음(명세 §6). 다음 요청이 `PAGE_ENDED`라 클라이언트가 새 페이지로 다시 로딩
  - INFO-3: `doodle` 레이어 하나에 id 순서로 그림. 청크별 `Group`으로 나누면 겹친 doodle의 위아래가 청크 경계에서 뒤집혀서 바꿈. 비트맵 캐시는 성능 문제가 보이면 도입(ARCHITECTURE §3 갱신)

## QA 기록 (/gf-qa가 추가)
- 2026-10-22 · `9476957` · 통과 · 버그 0 · PASS
  - 자동: backend `./gradlew test` 135개 통과(실패 0, 스킵 0, 새 테스트 `ChunkListTest`, `CanvasReadIT` 포함). frontend `npm test` 38개 통과, `npm run lint` 통과, `npm run build` 통과
  - 수동(헤드리스 Chromium 1280×720, 로컬 MySQL + `java -jar graffiti.jar` + Vite): SQL로 그림 1개(청크 4개 걸침), 두 줄 텍스트 1개, DELETED 1개를 넣음 → 첫 화면 중앙에 그림과 텍스트가 보이고 DELETED는 안 보임 / 휠 확대 시 포인터 아래 지점 고정 / 드래그 이동 후 축소 시 미니맵 사각형이 함께 움직임 / `chunks=999` → 400 `out_of_range`
  - 미검증: 실제 터치 기기의 핀치 줌(코드와 수학만 확인, 브라우저 QA는 마우스). 미니맵의 점유 칸 표시는 게시(06)가 점유율을 갱신한 뒤에 확인 가능(지금은 IT로 비트맵 형식만 확인)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
