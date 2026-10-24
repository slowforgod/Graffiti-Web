# 08-my-doodles: 내 낙서 고르기, 지우기, PNG로 저장

- **상태**: MERGED (PR #41, 2026-10-24)
- **승인자**: 현 (slowforgod) / 승인일: 2026-10-24 (스레드 "M5 마무리와 보안 점검 구현"의 요청: "결정이 필요한 부분은 보류하고 구현". standing 지시 "승인 받지말고 다 해". 아래 §9의 보류 결정은 추천안으로 구현)
- **브랜치**: `feat/08-my-doodles`
- **선행 명세**: 06

## 1. 왜 (Why)
06까지는 한 번 게시한 낙서를 되돌릴 방법이 없다. 잘못 그린 낙서를 본인이 지울 수 있어야 하고(D-13), 내가 그린 것만 그림 파일로 간직할 수 있어야 한다. 다른 사람에게는 여전히 작성자를 보이지 않는다(D-22).

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-13 | 내 doodle만 삭제(선택 삭제 포함), PNG는 클라이언트에서만 |
| 결정 | D-14, D-22 | 지우면 칸 참조 −1. 다른 사람에게 작성자 정보 없음 |
| 불변 조건 | INV-01/02 | 상태 변경은 큐 안(`RemoveDoodles`), 행 읽기와 칸 계산은 큐 밖 |
| 불변 조건 | INV-03/04 | 점유율이 내려가도 카운트다운 유지. 끝난 페이지는 `PAGE_ENDED` |
| 불변 조건 | INV-07/08/12 | 행은 `DELETED`로 남김. 쿨다운 반환 없음. 점유율은 PUBLISHED에서 재계산되므로 일치 |
| 보안 | SEC-01/02 | 조건부 UPDATE(`author_id`, `status`), 엄격한 본문 파싱, 작성자는 세션에서만 |
| 보안 | SEC-03/07 | CSRF, 남의 doodle은 403, 비로그인 401, 차단 403 |
| 보안 | SEC-08/09 | 일괄 삭제 50개 상한, 본문 2MB 상한. 요청 속도는 nginx `/api/` 제한(11에서 재점검) |
| 보안 | SEC-13 | 목록 응답에 작성자·payload 없음 |
| API | `API.md` §3 | 목록, 삭제, 일괄 삭제 응답과 에러 |
| DB | `DB.md` §4 | 목록, 삭제 쿼리 (스키마 변경 없음: `removed_at`, `removed_by`는 V4에 있음) |

## 3. 범위
**포함**
- 백엔드: `GET /api/me/doodles`, `DELETE /api/doodles/{id}`, `POST /api/doodles/delete`, 큐 명령 `RemoveDoodles`, `PostStore.deleteOwn`, 이벤트 `DoodleRemoved` → `doodle:removed`
- 프론트: 툴바 "내 낙서" 버튼, 내 낙서 패널(개수, 모두 고르기, 고른 낙서 지우기 + 확인, PNG로 저장), 캔버스에서 내 낙서 테두리 표시와 눌러서 고르기, 지운 낙서 즉시 제거, PNG 그리기(`png.ts`)

**제외 (NOT in scope)**
- 관리자 삭제·일괄 삭제(`/api/admin/doodles/**`), 차단과 일괄 숨김 → 10 나머지
- 보류·거부된 내 낙서를 작성자에게 보여 주기·알리기 → §9 결정 3 (보류)
- 끝난 페이지의 내 낙서 보기·저장 → 아카이브(D-17, TODOS)

## 4. 현재 상태 (이 PR 전)
- `doodle` 테이블에 `DELETED` 상태와 `removed_at`, `removed_by` 컬럼이 있음(V4). 쓰는 코드 없음
- 프론트 `chunkSync.ts`가 `doodle:removed`를 이미 처리함(03). 서버는 보내는 곳이 없었음
- `graffiti.query.max-batch-delete: 50` 설정이 있음(00)

## 5. 설계

```
삭제 (REST, 큐 밖)                                             큐 (RemoveDoodles)
 MyDoodleController: id 형식, 일괄이면 1~50개·중복 제거
 MyDoodleService: 행 읽기 → 없음 404 → 남의 것 403 → 끝난 페이지 409 PAGE_ENDED → PUBLISHED 아님 409 STATE_CONFLICT
                  StoredFootprint로 칸 계산, doodle_chunk 읽기 ─────────▶ requireCurrent(pageId)
                                                                        tx: doodle마다 UPDATE ... DELETED
                                                                            WHERE author_id=나 AND status='PUBLISHED'
                                                                            0행 → STATE_CONFLICT (전부 롤백)
                                                                            칸 −1 (복사본)
                                                                        커밋 뒤: 메모리 칸 교체
                                                                          DoodleRemoved → 청크마다 doodle:removed
                                                                          OccupancyChanged → minimap:diff (비워진 칸만)
```

**PNG 저장** (D-13, 서버 API 없음): 목록의 bbox가 걸친 청크를 `GET /api/pages/{p}/doodles`로 `maxChunks`개씩 읽어 내 id만 남기고, 2D 캔버스에 흰 배경 + 획(둥근 끝)·점·텍스트(Konva 기본 글꼴 Arial, 줄 간격 = 글자 크기)로 그린 뒤 `toBlob` → 내려받기 `내-낙서-{seq}장.png`. 고른 것이 있으면 고른 것만, 없으면 이 페이지의 내 낙서 전부.

| 파일 | 역할 |
|---|---|
| `backend/.../doodle/MyDoodleController.java`, `MyDoodleService.java`, `MyDoodleQuery.java` | API, 검사 순서, 읽기 |
| `backend/.../page/PageCommand.java`, `PageCommandHandler.java`, `PostStore.java`, `event/DoodleRemoved.java` | 큐 명령, 조건부 UPDATE |
| `backend/.../doodle/JdbcPostStore.java` | `deleteOwn` |
| `backend/.../realtime/RealtimeEvents.java`, `PageEventRelay.java` | `doodle:removed` |
| `frontend/src/mine/*` | API 호출, 선택·삭제·저장 훅, PNG |
| `frontend/src/ui/MyDoodlesPanel.tsx`, `ui/Toolbar.tsx`, `canvas/CanvasView.tsx`, `canvas/CanvasScreen.tsx`, `canvas/chunkSync.ts` | 화면 |

## 6. 실패 모드
| 경로 | 실패 상황 | 처리 | 테스트 |
|---|---|---|---|
| 삭제 | 남의 doodle id | 403, 아무것도 안 지움 | `MyDoodlesIT.onlyTheAuthorMayDelete…`, `oneDoodleOfSomeoneElse…` |
| 삭제 | 같은 doodle을 두 번(두 탭) | 큐가 한 줄로 처리, 뒤의 것은 0행 → 409 | `MyDoodlesIT.onlyTheAuthorMayDelete…`, `aSelectionThatChangedMeanwhile…` |
| 삭제 | 일괄 중 하나가 그사이 지워짐 | 409, 전부 롤백, 메모리 칸·이벤트 그대로 | `MyDoodlesIT.aSelectionThatChangedMeanwhile…` |
| 삭제 | 다른 사람과 같은 칸 | 칸 유지(참조 카운트) | `MyDoodlesIT.cellsSharedWith…` |
| 삭제 | 끝난 페이지, 보류된 것, 차단됨, CSRF 없음 | 409 / 409 / 403 / 403 | `MyDoodlesIT` |
| 본문 | 51개, 숫자 id, `"01"`, `userId` 끼워 넣기, JSON 아님 | 400 | `MyDoodlesIT.theSelectionBodyIsChecked` |
| 화면 | 삭제 거절 | 서버 문구 표시, 낙서 그대로 | `CanvasScreen.test` |
| 화면 | 저장 중 실패 | "PNG를 만들지 못했어요." | (수동) |

## 7. 수용 기준
- [x] AC-1: 목록은 그 페이지의 내 PUBLISHED doodle id·bbox만, 작성자·payload 없음. 비로그인 401, 동의 전 403, 끝난 페이지 409
- [x] AC-2: 지우면 DELETED + `removed_at`·`removed_by`, 칸 −1, `doodle:removed`(저장된 청크마다), 청크 조회에서 사라짐, 쿨다운 그대로
- [x] AC-3: 일괄 삭제는 전부 아니면 전무, 50개 상한, 엄격한 본문
- [x] AC-4: 남의 것 403, 없음 404, 끝난 페이지 409 `PAGE_ENDED`, 게시 상태 아님 409 `STATE_CONFLICT`, 차단 403
- [x] AC-5(프론트): "내 낙서" → 패널, 캔버스에서 고르기, 확인 뒤 삭제, 화면에서 즉시 사라짐, 차단된 사용자는 지우기 없음, 비로그인은 버튼 없음
- [x] AC-6(프론트): PNG는 내 낙서만, 흰 배경, 여백 16px, 캔버스 밖으로 넘지 않음

## 8. 테스트 계획
| 층 | 대상 | 파일 |
|---|---|---|
| 통합 | 목록, 삭제, 일괄 삭제, 권한, 롤백, 칸 공유 | `MyDoodlesIT` (12개) |
| 단위 | 청크 묶음 읽기, 삭제 호출 경로 | `mine/myDoodles.test.ts` |
| 단위 | 그림 범위, 그리기 호출 | `mine/png.test.ts` |
| 컴포넌트 | 패널, 고르기, 확인, 거절, 차단, 비로그인 | `CanvasScreen.test.tsx` |

## 9. 결정 (2026-10-24 현 확정: 전부 추천안)
| # | 무엇을 | 추천안 (구현됨) | 바꾸면 영향 |
|---|---|---|---|
| 1 | 목록 응답 모양 | id + bbox (payload 없음, PNG는 청크 조회로 읽음). 한 페이지에 최대 336개(7일 ÷ 30분)라 가볍게 | payload까지 주면 `MyDoodleQuery`, `MyDoodleController`, `mine/myDoodles.ts`(`loadDoodles` 없앰), API.md §3 |
| 2 | 끝난 페이지의 내 낙서 삭제 | 불가(`PAGE_ENDED`). 끝난 페이지의 최종 점유율·비트맵이 바뀌지 않게 | 허용하면 `MyDoodleService.delete`, `RemoveDoodles`(큐 밖 처리 필요), 아카이브 정책(D-17) |
| 3 | 보류·거부된 내 낙서 알림(07 틀에서 08로 미룸) | 이번엔 안 함. 목록에도 넣지 않고, 작성자는 게시 때 받은 "검토 후 게시돼요"만 봄 | 하면 목록에 `status` 추가 또는 `/user/queue/notice` 알림 + 화면 문구, API.md §3·§6 |
| 4 | 보류된 내 낙서를 작성자가 지우기 | 불가(`STATE_CONFLICT`). 관리자 검토 대상이라 | 허용하면 `deleteOwn`의 상태 조건과 관리자 목록 |
| 5 | PNG 배경 | 흰색(흰 펜도 보이게) | 투명으로 바꾸면 `png.ts`의 `PNG_BACKGROUND` 한 줄 |
| 6 | 지우기 전 확인 | 패널 안에서 한 번 더 묻기("되돌릴 수 없고 게시 대기 시간도 돌아오지 않아요") | 없애면 `MyDoodlesPanel`, `useMyDoodles` |

## 10. 롤백
PR revert. 스키마 변경이 없으므로 DELETED 행은 그대로 남고(INV-07), 되돌린 뒤에도 재시작 복구가 PUBLISHED만 읽어 점유율이 맞다.

---

## 리뷰 기록 (/gf-review가 추가)

## QA 기록 (/gf-qa가 추가)

## 보안 감사 기록 (/gf-security가 추가, 해당 시)
11-security-pass의 전체 감사(`docs/security-audits/2026-10-24.md`)에 포함.

## 배포 기록 (/gf-deploy-check가 추가)
