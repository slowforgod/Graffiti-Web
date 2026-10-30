# 10a-admin-takedown: 통과 낙서 목록과 게시된 낙서 거부

- **상태**: IN_PROGRESS
- **승인자**: 현 (요청 그대로 진행) / 승인일: 2026-10-30
- **브랜치**: `feat/10-admin-takedown-passed`
- **선행 명세**: 07-moderation-frame, 07-moderation

## 1. 왜 (Why)
필터가 놓친 낙서(예: 손글씨 욕설, 손가락 욕 그림)가 `PASSED`로 게시되면 관리자가 찾아볼 목록도, 내릴 방법도 없었다. 관리자가 통과한 낙서를 전부 보고, 잘못 통과한 것을 낙서장에서 내릴 수 있어야 한다. 내린 낙서는 지우지 않고 "관리자가 거부한 낙서"로 남겨, 나중에 부적절 데이터셋으로 모은다(D-47).

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-23, D-47 | 관리자 검토. 게시된 낙서 거부는 보류 거부와 같은 부류(REJECTED + ADMIN REJECT) |
| 결정 | D-39 | 관리자 판정을 정답 라벨로 씀 |
| 불변 조건 | INV-01, INV-07, INV-12 | 큐를 거침, 행은 지우지 않음, 칸은 PUBLISHED에서만 셈 |
| 보안 | SEC-01, SEC-14 | 목록 조건은 enum 고정 SQL, 조치는 `moderation_action`에 기록 |
| API | `API.md` §4 | `moderation=PASSED` 목록, `reject`가 PUBLISHED도 받음 |
| DB | `DB.md` §2, §4 | `removed_at`/`removed_by`를 관리자 거부에도 씀, 데이터셋 쿼리 |

## 3. 범위
**포함**
- `GET /api/admin/doodles?moderation=PASSED`: 게시 중이고 AI 판정이 `PASSED`인 낙서, 최신순, 모든 페이지
- `POST /api/admin/doodles/{id}/reject`가 HELD뿐 아니라 PUBLISHED(PASSED·UNCHECKED·관리자 게시 모두)도 받음 → `status=REJECTED`
- 현재 페이지면 칸을 비우고 `doodle:removed`·`minimap:diff`를 보냄. 끝난 페이지면 행만 바꿈(칸 기록은 그대로)
- 관리자 화면: "통과 게시" 탭, 통과·미검사 게시 카드에 "거부하고 내리기" 버튼, 거부 탭에서 "게시된 뒤 관리자가 내림" 표시, 조치 기록에 "게시 중 내림"
- 잘못 내린 낙서는 기존 "복구해서 게시"(REJECTED → PUBLISHED, 현재 페이지만)로 되돌림

**제외 (NOT in scope)**
- 데이터셋 추출 스크립트: 쿼리만 DB.md §4에 둠. 추출본은 git에 넣지 않음(SEC-17)
- 사유 입력: `reason`은 비워 둠. 필요하면 10에서
- 일괄 거부: 10의 일괄 삭제와 함께

## 4. 현재 상태 (검증된 사실)
- `AdminReviewService.reject`는 HELD만 받고 나머지는 `STATE_CONFLICT`
- `AdminDoodleQuery.Filter`는 HELD, REJECTED, UNCHECKED
- 캔버스는 `doodle:removed`를 이미 처리함(08 내 낙서 삭제)

## 5. 설계
```
POST /api/admin/doodles/{id}/reject
  큐 밖: 행 읽기 → HELD면 RejectHeld (기존)
                 → PUBLISHED면 현재 페이지 확인, 현재면 칸 계산(StoredFootprint) → RejectPublished
  큐 안(RejectPublished): 한 트랜잭션
        UPDATE doodle SET status='REJECTED', removed_at, removed_by=관리자 WHERE status='PUBLISHED' AND page_id
        moderation_action(ADMIN, REJECT, detail={"from":"PUBLISHED"})
        (현재 페이지일 때) 칸 −1
     커밋 뒤: 메모리 칸 교체, DoodleRemoved(청크마다 doodle:removed), 비워진 칸 있으면 OccupancyChanged
     0행이면 STATE_CONFLICT
```
- `moderation_state`는 그대로 둔다(AI가 무엇을 놓쳤는지 남음). 보류 거부는 `HELD`, 게시 후 거부는 `PASSED`/`UNCHECKED`로 구분된다
- 복구(`PublishWithheld`)는 `removed_at`/`removed_by`를 지운다

## 6. 수용 기준
- [x] AC-1: PASSED 목록은 게시 중인 PASSED만, 최신순, 작성자 포함. UNCHECKED는 빠짐
- [x] AC-2: 게시된 낙서 거부 → REJECTED, payload·moderation_state 유지, removed_by=관리자, `moderation_action(ADMIN, REJECT, from=PUBLISHED)`, `doodle:removed`, 칸 비움, 청크 조회에서 빠짐, REJECTED 목록에 나옴. 다시 하면 `STATE_CONFLICT`
- [x] AC-3: 내린 낙서를 복구하면 PUBLISHED, removed_* 비움, 청크 조회에 다시 나옴
- [x] AC-4: 끝난 페이지의 게시 낙서도 거부됨(브로드캐스트 없음)
- [x] AC-5(프론트): 통과 게시 탭, "거부하고 내리기" 후 목록에서 빠짐, 거부 탭에 "게시된 뒤 관리자가 내림"

## 7. 테스트
| 층 | 대상 | 파일 |
|---|---|---|
| 통합 | AC-1~4 | `AdminReviewIT` |
| 프론트 | AC-5 | `AdminScreen.test.tsx` |
