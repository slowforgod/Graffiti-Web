# 07-moderation-frame: 검사기를 끼우는 틀, 보류(HELD), 관리자 검토

- **상태**: MERGED (PR #40, 2026-10-24)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-24 (스레드 "M4 안전 구현 (기준 확정 전)"의 요청: "필터링은 아니더라도 API랑 연결까지만". standing 지시 "승인 받지말고 다 해")
- **브랜치**: `feat/07-moderation-frame`
- **선행 명세**: 06
- **관계**: 현이 로컬에서 쓰는 `specs/07-moderation.md`(DRAFT), `FILTER.md`, D-42(PROPOSED)의 **방향만 따른 틀**이다. 검사 기준(예시, 금칙어, 정규화, 프롬프트)과 실제 검사기(ChatKHU, OpenAI, 렌더링, ML 호출)는 그 명세가 확정된 뒤 이 틀에 끼운다. 이 명세는 그 문서들을 바꾸지 않는다.

## 1. 왜 (Why)
06은 모든 낙서를 검사 없이 `UNCHECKED`로 게시해서 공개 배포할 수 없다. 필터링 기준이 아직 정해지지 않았지만, 검사 결과를 받는 쪽(게시·보류·거부 세 갈래, 기록, 관리자 검토)은 기준과 상관없이 먼저 만들 수 있다. 이 틀이 있으면 기준이 정해졌을 때 **검사기 구현과 목록 파일만** 추가하면 된다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-18, D-19 | 큐 밖 사전 검사, 텍스트 금칙어 → ChatKHU → OpenAI, 그림 → OpenAI(이미지). 제공자는 인터페이스 뒤에 |
| 결정 | D-42 (PROPOSED, 현 로컬) | 애매·실패는 `HELD`. 이 틀은 그 방향으로 만들되 확정 전이라 설정으로 바꿀 수 있게 둠 |
| 결정 | D-12, D-28 | 거부·보류도 쿨다운 소모. 저장 전에 페이지가 넘어가면 되돌림 |
| 결정 | D-23 | 관리자가 보류·거부를 보고 게시·거부 |
| 결정 | D-34~D-40 | ML은 `ml.mode`로. SHADOW면 기록만 |
| 불변 조건 | INV-01/02 | 검사는 큐 밖, 저장과 관리자 게시는 큐 안 |
| 불변 조건 | INV-05 | PASSED·UNCHECKED만 자동 게시. HELD·REJECTED는 관리자 게시로만 |
| 불변 조건 | INV-13 | ML 실패가 게시를 막지 않음 (ENFORCE에서도 ERROR는 무시) |
| 보안 | SEC-05 | `moderation_detail`과 로그에 원문 글자·검사기 원문 응답 없음. 카테고리·메모는 대문자 코드만 |
| 보안 | SEC-07, SEC-14 | 관리자 API는 `@CurrentUser(ADMIN)` + CSRF, 모든 결정은 `moderation_action` |
| API | `API.md` §0, §3, §4 | `202` 보류, `422` details, 관리자 검토 API, `STATE_CONFLICT` |
| DB | `DB.md` §2, §3 | V5: `HELD`, `moderation_action` 테이블, `moderation_detail` 형식 |

## 3. 범위
**포함**
- 백엔드 `moderation`: `ModerationCheck`(검사기 하나), `CheckResult`(PASS/BLOCK/REVIEW/ERROR/SKIPPED + 카테고리 + 메모), `StubCheck`, `ModerationChecks`(설정 이름 → 구현, 없으면 스텁, 모르는 이름이면 시작 실패), `VerdictCombiner`(순수), `ModerationService`(순서대로·타임아웃·BLOCK에서 멈춤·ML 그림자), `WordFilterCheck` + `WordList` + `TextNormalizer`(기본 정규화만)
- 목록 파일 `resources/moderation/banned-words.txt`, `allowed-words.txt` (**비어 있음** → 금칙어 검사는 SKIPPED)
- 게시 흐름: 결과에 따라 `PublishDoodle` / `WithholdDoodle`(REJECTED·HELD 저장 + `moderation_action(AI)`), 응답 201 / 202 / 422
- 관리자 API(일부): 검토 목록, 보류·거부 게시(`restore`), 보류 거부(`reject`), 조치 기록 조회. 큐 명령 `PublishWithheld`, `RejectHeld`
- 관리자 화면 `/admin`: 보류·거부·미검사 탭, Konva 미리보기, 게시·거부 버튼. 상단 바에 관리자용 링크
- 게시 화면: 202면 "검토 후 게시돼요" + 초안 지움, 422면 안내 + 초안 남김, 둘 다 쿨다운 표시
- 설정 `graffiti.moderation.unchecked`, `text-checks`, `drawing-checks`, `graffiti.query.admin-page-size-max`
- 마이그레이션 V5, 문서(API, DB, ARCHITECTURE)

**제외 (NOT in scope)**
- 실제 검사기: ChatKHU 호출·프롬프트·응답 파싱, OpenAI moderation(텍스트·이미지), `DoodleRenderer`(PNG), ML 서버 호출과 `ml_prediction` 기록 → 기준 확정 후 `specs/07-moderation.md`와 07b
- FILTER.md §2의 닮은꼴 바꾸기·자모 분해·한영 전환 규칙, 개인정보 정규식 → 기준 확정 후 `TextNormalizer`/`WordList`에
- 관리자 삭제, 일괄 삭제, 차단·해제, 적발 순 사용자 목록, 상세 조회, UNCHECKED 표시 해제 → 10 나머지
- 관리자 API 속도 제한 → 11
- 작성자에게 보류 결과 알림 → 08

## 4. 현재 상태 (이 PR 전)
- `ModerationService.moderate`가 항상 `UNCHECKED`, `ModerationState`에 `HELD` 없음, 거부되면 `IllegalStateException`
- `V4__doodle.sql`에 `HELD` 없음, `moderation_action` 테이블 없음(DB.md에만)
- `admin` 패키지 없음, 프론트에 관리자 화면 없음

## 5. 설계

```
PublishService (큐 밖)            ModerationService                               큐
 │ ③ ReservePost 끝                 │                                               │
 │── moderate(kind, payload) ─────▶│ text-checks / drawing-checks 순서대로             │
 │                                  │   각각 timeout(5s), BLOCK이면 멈춤              │
 │                                  │   예외·시간 초과·형식 틀림 → ERROR                │
 │                                  │ ml (mode≠OFF): SHADOW면 기록만                  │
 │                                  │ VerdictCombiner:                               │
 │                                  │   BLOCK → REJECTED                             │
 │                                  │   REVIEW·ERROR → HELD                          │
 │                                  │   모두 PASS → PASSED                            │
 │                                  │   스텁 포함 → UNCHECKED → unchecked=HOLD면 HELD │
 │◀── Verdict(state, detail, cat) ─│                                               │
 │ PASSED/UNCHECKED ── PublishDoodle ───────────────────────────────────────────▶│ 06 그대로 → 201
 │ REJECTED/HELD ──── WithholdDoodle ───────────────────────────────────────────▶│ tx: INSERT doodle(status), doodle_chunk,
 │                                                                                 │     moderation_action(AI, REJECT|HOLD)
 │                                                                                 │ 페이지 끝났으면 PAGE_ENDED → 되돌림
 │ → 422 MODERATION_REJECTED {category, cooldownEndsAt} / 202 {held, cooldownEndsAt}

관리자 (/admin) ── POST /api/admin/doodles/{id}/restore ── PublishWithheld ──▶ 큐: 현재 페이지? 상태 그대로?
                                                                                tx: UPDATE status=PUBLISHED, moderation_action(ADMIN)
                                                                                커밋 뒤 점유율, doodle:published
               ── POST /api/admin/doodles/{id}/reject ─── RejectHeld ──────▶ 큐: UPDATE HELD→REJECTED, moderation_action
```

**검사기를 끼우는 방법** (기준 확정 후)
1. `ModerationCheck`를 구현한 `@Component`를 만들고 `name()`을 설정 이름(`chatkhu`, `openai-text`, `openai-image`, `ml`)으로 둔다. 같은 이름의 스텁이 자동으로 빠진다
2. 금칙어는 `banned-words.txt`(한 줄에 `단어 [카테고리]`)와 `allowed-words.txt`만 채우면 바로 동작한다
3. 순서와 사용 여부는 `graffiti.moderation.text-checks`, `drawing-checks`
4. 모든 검사기가 실제 구현이 되면 결과가 PASS만 나와 `PASSED`가 되고, `unchecked` 설정은 더 이상 쓰이지 않는다

**`unchecked` 기본값 `HOLD`**: 스텁만 지난 낙서를 `PASSED`나 `UNCHECKED`로 게시하면 검사된 것처럼 보이거나 06과 같은 미검사 공개가 된다. 그래서 기본은 보류(D-42 방향)이고, 로컬에서 그리기를 시험할 때만 `MODERATION_UNCHECKED=PUBLISH`로 바꾼다. 통합 테스트는 06 테스트를 그대로 쓰려고 `PUBLISH`로 돈다.

| 파일 | 역할 |
|---|---|
| `backend/.../moderation/ModerationCheck.java`, `CheckResult.java`, `CheckOutcome.java`, `ModerationInput.java` | 검사기 계약 |
| `backend/.../moderation/StubCheck.java`, `ModerationChecks.java` | 설정 이름 → 구현 또는 스텁 |
| `backend/.../moderation/VerdictCombiner.java` | 판정 합성 (순수) |
| `backend/.../moderation/ModerationService.java`, `ModerationState.java` | 실행, `HELD` |
| `backend/.../moderation/WordFilterCheck.java`, `WordList.java`, `TextNormalizer.java` | 금칙어 (목록 비어 있음) |
| `backend/src/main/resources/moderation/*.txt` | 목록 파일 |
| `backend/.../page/PageCommand.java`, `PageCommandHandler.java`, `PostStore.java` | `WithholdDoodle`, `PublishWithheld`, `RejectHeld`, `Action` |
| `backend/.../doodle/PublishService.java`, `DoodleController.java`, `JdbcPostStore.java`, `StoredFootprint.java` | 세 갈래, 202/422, 저장 |
| `backend/.../admin/*` | 관리자 검토 API |
| `backend/src/main/resources/db/migration/V5__moderation.sql` | ENUM, `moderation_action` |
| `frontend/src/admin/*`, `App.tsx`, `auth/AuthBar.tsx`, `canvas/CanvasScreen.tsx`, `tools/publish.ts` | 화면 |

## 6. 실패 모드
| 경로 | 실패 상황 | 처리 | 테스트 |
|---|---|---|---|
| 검사 | 검사기가 시간 초과, 예외, 이름이 다른 결과, 자유 문장 카테고리 | 그 검사 ERROR → HELD, 로그는 예외 종류만 | `ModerationServiceTest` |
| 검사 | 설정에 오타(`word-filtre`) | 시작 실패 | `ModerationChecksTest` |
| 검사 | 실제 검사 없이 스텁만 | `unchecked`대로 HELD(기본) 또는 UNCHECKED 게시 | `ModerationServiceTest`, `VerdictCombinerTest` |
| ML | SHADOW에서 BLOCK / ENFORCE에서 ERROR | 판정 영향 없음(INV-13) | `ModerationServiceTest` |
| 저장 | 보류 저장 전에 페이지 넘어감 | 409 `PAGE_ENDED`, 쿨다운 되돌림, 행 없음 | `PublishIT` |
| 저장 | `moderation_action` INSERT 실패 | 롤백, 쿨다운 되돌림, 500 | `PublishIT` |
| 관리자 | 끝난 페이지의 보류 게시 | 409 `PAGE_ENDED`, 보류 그대로, 화면은 버튼 비활성 | `AdminReviewIT`, `AdminScreen.test` |
| 관리자 | 두 관리자가 같은 보류를 동시에 처리 | 큐가 한 줄로 처리, 뒤의 것은 조건부 UPDATE 0행 → 409 `STATE_CONFLICT` | `AdminReviewIT` |
| 관리자 | 일반 사용자, CSRF 없음 | 403 `FORBIDDEN` / `CSRF_INVALID` | `AdminReviewIT` |

## 7. 수용 기준
- [ ] AC-1: 검사기가 BLOCK이면 422 `MODERATION_REJECTED` `details.category`·`cooldownEndsAt`, `doodle(REJECTED)`, `moderation_action(AI, REJECT)`, 쿨다운 소모, 브로드캐스트 없음
- [ ] AC-2: REVIEW·ERROR(또는 기본 설정에서 스텁만)면 202 `{held, cooldownEndsAt}`, `doodle(HELD)` + `doodle_chunk`, `moderation_action(AI, HOLD)`, 점유율·브로드캐스트 없음
- [ ] AC-3: 모두 PASS면 201, `PASSED`. 스텁이 있고 `unchecked=PUBLISH`면 06과 같이 `UNCHECKED` 게시
- [ ] AC-4: 검사기마다 타임아웃, BLOCK에서 멈춤, 결과 형식이 틀리면 ERROR. `moderation_detail`과 로그에 원문 없음
- [ ] AC-5: ML SHADOW는 기록만, ENFORCE의 ERROR는 무시
- [ ] AC-6: 관리자가 현재 페이지의 HELD·REJECTED를 게시하면 PUBLISHED, 점유율, `doodle:published`, `moderation_action(ADMIN, APPROVE|RESTORE)`. 끝난 페이지는 `PAGE_ENDED`
- [ ] AC-7: 관리자가 HELD를 거부하면 REJECTED + `moderation_action(ADMIN, REJECT)`, 다시 하면 `STATE_CONFLICT`
- [ ] AC-8: 관리자 목록은 작성자 이메일·닉네임·payload·검사 기록을 주고, 일반 사용자는 403
- [ ] AC-9(프론트): 202 → "검토 후 게시돼요" + 초안 지움, 422 → 안내 + 초안 남김, 둘 다 쿨다운. `/admin`에서 목록·게시·거부

## 8. 테스트 계획
| 층 | 대상 | 파일 |
|---|---|---|
| 단위 | 판정 합성 모든 조합 | `VerdictCombinerTest` |
| 단위 | 실행 순서, 타임아웃, 예외, 형식, ML 모드 | `ModerationServiceTest` |
| 단위 | 스텁 대체, 모르는 이름 | `ModerationChecksTest` |
| 단위 | 목록 파일 파싱, 정규화, 예외 단어, 출고 목록이 비어 있음 | `WordListTest` |
| 통합 | 보류·거부 저장, 쿨다운, 페이지 종료, INSERT 실패 | `PublishIT` |
| 통합 | 관리자 목록·게시·거부·권한·조치 기록 | `AdminReviewIT` |
| 컴포넌트 | 202·422 안내, 관리자 화면 | `CanvasScreen.test`, `AdminScreen.test` |

## 9. 기준이 확정되면 바뀔 부분
- `banned-words.txt`, `allowed-words.txt` 채우기 (FILTER.md §3), `WordListTest`에 FILTER.md 예시 추가
- `TextNormalizer`에 FILTER.md §2의 닮은꼴·자모·한영 전환 규칙, `WordList`에 개인정보 정규식(1차 차단 여부는 07 초안 결정 6)
- `ChatKhuTextProvider`(`chatkhu`), `OpenAiModerationProvider`(`openai-text`, `openai-image`), `DoodleRenderer`, `MlModerationProvider`(`ml`) 구현 + 설정(`graffiti.moderation.chatkhu.*`, `openai.*`)
- `moderation_detail` 형식은 지금 틀(검사별 outcome)로 두고, 07 초안의 제공자별 형식이 필요하면 그때 맞춤
- D-42가 ACCEPTED가 되면 DECISIONS.md, SEC-15(타임아웃 → HELD), INV-05 문구 정리. 다르게 정해지면(예: 실패는 통과) `VerdictCombiner`만 바꿈
- 07 초안 결정 3(보류도 쿨다운 소모), 4(끝난 페이지 보류는 게시 불가), 5(거부 후 초안 남김)는 추천안대로 구현해 둠. 바뀌면 `PublishService`, `AdminReviewService`, `CanvasScreen`

## 10. 롤백
PR revert + V5 되돌림: `HELD` 행을 `REJECTED`로 바꾼 뒤 ENUM을 V4 값으로 되돌리고 `moderation_action`은 남긴다(INV-07). 되돌리면 06 상태라 공개 배포 금지.

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
