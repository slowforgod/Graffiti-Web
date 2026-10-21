---
name: gf-review
description: graffiti 머지 전 diff 리뷰. base 브랜치 대비 변경을 전부 읽고 checklist.md의 CRITICAL(불변 조건, 보안, 경쟁 조건, LLM 출력, Gate Integrity)과 INFORMATIONAL 항목으로 검사한다. 기계적인 문제는 바로 고치고(AUTO-FIX), 판단이 필요한 것은 묻는다(ASK). 결과를 명세의 리뷰 기록에 남긴다. "리뷰해줘", "diff 확인", "머지 전 점검"에 사용.
---

# /gf-review — 머지 전 diff 리뷰

## 절대 규칙
- **diff 전체를 먼저 읽고** 판단한다. 이미 diff에서 해결된 것을 지적하지 않는다.
- **진짜 문제만** 짧게: 한 줄 문제, 한 줄 수정.
- **커밋, push, PR은 하지 않는다**(그건 `/gf-ship`). AUTO-FIX 수정은 작업 트리에만 반영한다.
- Gate Integrity 항목은 **절대 스스로 결정하지 않는다**. 항상 ASK.

## 단계

### 1. 대상 확정
- 현재 브랜치와 base(`main`)를 확인한다: `git fetch origin main && git diff origin/main...HEAD --stat`
- 커밋되지 않은 변경도 포함한다: `git diff HEAD --stat`
- 브랜치 이름이나 커밋 메시지로 관련 명세 `docs/specs/NN-*.md`를 찾는다. 없으면 사용자에게 묻는다.
- 명세의 "근거 문서" 표에서 이번 변경이 건드리는 INV/SEC 목록을 얻는다.

### 2. 읽기
- `.claude/skills/gf-review/checklist.md`를 읽는다.
- 전체 diff를 읽는다: `git diff origin/main...HEAD` + 미커밋 변경.
- 변경된 함수를 호출하는 쪽과 테스트를 함께 읽는다(diff 밖에서 깨지는 경우가 많다).

### 3. 검사 (checklist 순서대로)
1. **Pass 1 CRITICAL**: 불변 조건 위반, 보안(SEC), 경쟁 조건, LLM 출력 신뢰, Enum 완전성, Gate Integrity
2. **Pass 2 INFORMATIONAL**: 테스트 빈틈, 에러 처리, 매직 넘버, 죽은 코드, 성능, 문서 동기화
3. **옆으로 훑기(Sideways sweep)**: 문제를 하나 찾으면 **같은 패턴을 다른 진입점 전부에서** 찾는다(REST 컨트롤러, STOMP 핸들러, 스케줄러, 관리자 API). 발견한 형제 위치를 같은 항목 아래에 나열한다.
4. **즉시 CRITICAL**: 데이터 손실, 인증·권한 우회, 상한 없는 유료 API 호출. 어떤 이유로도 등급을 낮추지 않는다.

각 발견 사항 형식:
```
[C1] CRITICAL · 확신 8/10 · backend/.../DoodleController.java:42 · INV-01
문제: 컨트롤러가 cellCount를 직접 갱신함 (큐를 거치지 않음)
수정: PublishDoodle 커맨드로 이동
분류: ASK
```

### 4. 분류 (Fix-First)
`checklist.md`의 Fix-First 기준으로 AUTO-FIX와 ASK를 나눈다. 애매하면 ASK.

### 5. AUTO-FIX 적용
- 각각 한 줄로 보고: `[AUTO-FIXED] 파일:줄 문제 → 한 일`
- 고친 뒤 관련 테스트를 실행해서 깨지지 않았는지 확인한다.

### 6. ASK 처리
- **한 번에 하나씩** 묻는다. 각 질문에 추천을 붙인다: `추천: A — 이유`.
- 선택지 예: A) 수정 B) 이번 범위에서 제외하고 TODOS.md에 기록 C) 문제 아님(이유 기록)
- Gate Integrity 항목의 선택지: A) 되돌리기(게이트 복구) B) 유지 — 근거(측정값이나 결정)를 명세에 기록 C) 다른 방식으로 수정
- 승인된 수정을 적용하고 테스트를 실행한다.

### 7. 재검토와 기록
- 수정이 있었으면 수정된 부분만 다시 검사한다.
- 전체 테스트를 실행하고 결과 요약(통과/실패/스킵 수)을 확인한다.
- 명세의 `## 리뷰 기록`에 한 줄을 추가한다:
  `YYYY-MM-DD · <HEAD 커밋 SHA 7자리> · CRITICAL n(해결 n) · INFO n · AUTO-FIX n · ASK n · 상태`
  미커밋 변경이 있으면 SHA 뒤에 `+dirty`를 붙이고, `/gf-ship`이 커밋한 뒤 다시 확인하게 한다.

## 완료 상태
- `DONE` — 미해결 CRITICAL 0
- `DONE_WITH_CONCERNS` — CRITICAL은 없으나 미뤄진 INFO가 있음 (TODOS에 기록)
- `BLOCKED` — 미해결 CRITICAL 존재 (G3 실패)
- `NEEDS_CONTEXT` — 관련 명세를 찾지 못함, 또는 결정 변경이 필요한 문제 발견
