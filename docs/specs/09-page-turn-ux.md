# 09-page-turn-ux: 상단 상태바, T−5분 카운트다운, 페이지 넘김 처리

- **상태**: MERGED (PR #42, 2026-10-24)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-24 (스레드 "M5 마무리와 보안 점검 구현"의 요청. standing 지시 "승인 받지말고 다 해". §9의 보류 결정은 추천안으로 구현)
- **브랜치**: `feat/09-page-turn-ux`
- **선행 명세**: 04, 06

## 1. 왜 (Why)
지금 화면은 페이지가 언제 넘어가는지 보여 주지 않고, 넘어가면 아무 말 없이 새 페이지를 불러온다. 그리던 초안이 갑자기 사라지면 사용자는 버그로 느낀다. D-16대로 점유율과 남은 시간을 늘 보여 주고, 5분 전부터 크게 경고하고, 넘어간 뒤 무슨 일이 있었는지 알려 준다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-15 | 종료 = `min(생성+7일, 70%+6시간)`, 카운트다운 취소 없음 |
| 결정 | D-16 | 상단 상시 표시, T−5분 큰 카운트다운과 경고 문구, 넘어가면 초안 폐기와 자동 전환, 옛 pageId는 `PAGE_ENDED`(재전송 없음) |
| 결정 | D-06 | 넘김 규칙(문턱, 카운트다운 길이)은 페이지 행의 스냅샷 값 |
| 불변 조건 | INV-03/04 | 한 방향 전이, 옛 페이지로의 쓰기는 `PAGE_ENDED` |
| 불변 조건 | INV-09 | 새 페이지도 구독 → 조회 → 병합 |
| 불변 조건 | INV-10 | 화면 문구의 70%, 6시간은 하드코딩하지 않고 `/api/pages/current` 설정에서 |
| 보안 | SEC-02/07 | 옛 페이지 초안 SEND 거절 (기존 동작, 테스트 추가) |
| API | `API.md` §2 | `config.countdownThresholdPermille`, `countdownDurationSec` 추가, 클라이언트 동작 설명 |

## 3. 범위
**포함**
- 백엔드: `PageSnapshot`에 `PageRules`, `/api/pages/current`의 `config`에 넘김 규칙 두 값. 넘김 뒤 옛 페이지 초안 SEND가 `PAGE_ENDED`로 거절되고 같은 연결로 새 페이지 초안이 되는지 확인하는 테스트
- 프론트: 상단 상태바(제N장, 점유율 소수 한 자리, 남은 시간 또는 "N 뒤 넘어가요", 카운트다운 전에는 "70%가 차면 6시간 뒤 넘어가요"), T−`countdownWarningSec` 큰 카운트다운(분:초)과 "페이지가 넘어가면 그리고 있는 초안도 사라져요", 넘김 뒤 안내 문구, `page:turned`를 놓쳤을 때 종료 + 5초에 다시 불러오기, `page:status` 반영

**제외 (NOT in scope)**
- 넘김 순간의 애니메이션, 소리 → 필요하면 TODOS
- 서버의 카운트다운 푸시 → ARCHITECTURE §4.4대로 클라이언트 계산

## 4. 현재 상태 (이 PR 전)
- 서버: `PageTimer` → `EndPage` → `PageTurned`·`PageStatusChanged` → 옛 메타 토픽에 `page:turned`(02, 04). 옛 페이지 구독·쓰기는 `PAGE_ENDED`(04, 06). `/api/pages/current`에 `serverTime`, `scheduledEndAt`, `countdownWarningSec`
- 프론트: `page:turned`나 `PAGE_ENDED`면 `/api/pages/current`부터 다시 불러와 새 `PageCanvas`(초안은 언마운트로 사라짐). 상태바, 경고, 안내 없음. `MetaSync`는 `page:status`의 점유율만 씀

## 5. 설계

```
PageCanvas (페이지마다 새로 마운트)
  상태: status, scheduledEndAt ← /api/pages/current, page:status
        occupancy ← minimap(onMeta), page:status
  endMs = scheduledEndAt − (Date.now() + serverTime 차이)
  시계: endMs ≤ 경고 + 1분이면 1초마다, 아니면 30초마다 다시 계산
        endMs = 0 → 5초 안에 page:turned가 없으면 onPageEnded (같은 페이지가 오면 시각만 갱신)
  StatusBar  : 늘 표시
  TurnWarning: 0 < endMs ≤ countdownWarningSec
page:turned / PAGE_ENDED → onPageEnded(hadDraft) → CanvasScreen: 안내 문구 정하고 /api/pages/current
  → 새 PageCanvas (초안 없음) + "페이지가 넘어가 그리던 초안이 사라졌어요." | "새 페이지로 넘어왔어요."
```

| 파일 | 역할 |
|---|---|
| `backend/.../page/PageSnapshot.java`, `PageState.java`, `PageController.java` | 넘김 규칙을 설정 응답에 |
| `backend/.../realtime/RealtimeIT.java`, `doodle/CanvasReadIT.java` | 넘김 뒤 초안 거절, 설정 값 |
| `frontend/src/ui/pageTime.ts`, `StatusBar.tsx`, `TurnWarning.tsx` | 계산, 표시 |
| `frontend/src/realtime/metaSync.ts` | `onStatus` |
| `frontend/src/canvas/CanvasScreen.tsx`, `shared/pageConfig.ts`, `app.css` | 연결, 안내, 배치 |

## 6. 실패 모드
| 경로 | 실패 상황 | 처리 | 테스트 |
|---|---|---|---|
| 넘김 | 연결이 끊겨 `page:turned`를 놓침 | 종료 + 5초에 현재 페이지를 다시 불러옴. 서버가 아직 넘기기 전이면 같은 페이지의 최신 시각으로 다시 계산 | `CanvasScreen.test` "asks for the current page…" |
| 넘김 | 그리는 중에 넘어감 | 초안 폐기, 새 페이지에서 안내 | `CanvasScreen.test` "says on the new page…" |
| 넘김 | 옛 pageId로 늦게 온 초안 SEND | `/user/queue/errors`에 `PAGE_ENDED`, 연결 유지, 중계 없음 | `RealtimeIT.aDraftOfTheTurnedPage…` |
| 시계 | 클라이언트 시계가 틀림 | `serverTime` 차이로 보정 | `pageTime.test` |
| 표시 | 남은 시간이 59분 59초 | 올림으로 "1시간"(0분은 안 보임) | `pageTime.test` |

## 7. 수용 기준
- [x] AC-1: 상단에 제N장, 점유율, 남은 시간이 늘 보이고 `page:status`·`minimap:diff`를 따라 바뀜
- [x] AC-2: 카운트다운 전에는 페이지 설정의 문턱과 길이로 "70%가 차면 6시간 뒤 넘어가요"
- [x] AC-3: 남은 시간이 `countdownWarningSec` 이하면 왼쪽 위에 분:초 카운트다운과 D-16 경고 문구
- [x] AC-4: 넘어가면 새 페이지로 자동 전환, 초안 폐기, 초안이 있었으면 그 사실을 안내
- [x] AC-5: `page:turned`를 놓쳐도 종료 + 5초 뒤 새 페이지로
- [x] AC-6: 옛 pageId 초안은 `PAGE_ENDED`, 같은 연결로 새 페이지 초안 가능

## 8. 테스트 계획
| 층 | 대상 | 파일 |
|---|---|---|
| 통합 | 넘김 뒤 초안, 설정 값 | `RealtimeIT`, `CanvasReadIT` |
| 단위 | 남은 시간 계산·문구·틱 간격 | `ui/pageTime.test.ts` |
| 컴포넌트 | 상태바, 경고, 넘김 안내, 놓친 넘김 | `CanvasScreen.test.tsx` |

## 9. 결정 필요 (보류, 추천안으로 구현)
| # | 무엇을 | 추천안 (구현됨) | 바꾸면 영향 |
|---|---|---|---|
| 1 | 상태바 위치와 내용 | 화면 위 가운데: 제N장 · 점유율 71.2% · 남은 시간(카운트다운 중 "N 뒤 넘어가요") · 카운트다운 전 안내 한 줄. 좁은 화면에서는 제목 아래로 | `StatusBar.tsx`, `app.css` |
| 2 | 남은 시간 표기 | 1일 이상 "N일 N시간", 1시간 이상 "N시간 N분", 그 밑은 "N분"(올림). 경고 중에는 "4:59" | `pageTime.ts` |
| 3 | 넘김 뒤 안내 문구 | 초안이 있었으면 "페이지가 넘어가 그리던 초안이 사라졌어요.", 없으면 "새 페이지로 넘어왔어요." (누르면 닫힘) | `CanvasScreen.tsx`의 `TURNED`, `TURNED_DRAFT_LOST` |
| 4 | `page:turned`를 놓쳤을 때 기다리는 시간 | 5초 후 다시 불러오기 | `TURN_GRACE_MS` |

## 10. 롤백
PR revert. 설정 응답의 두 필드는 추가만 했으므로 되돌려도 다른 화면에 영향 없음.

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)
11-security-pass의 전체 감사(`docs/security-audits/2026-10-24.md`)에 포함.

## 배포 기록 (/gf-deploy-check가 추가)
