---
name: gf-run
description: graffiti 파이프라인 자동 실행. APPROVED 명세 하나를 받아 구현 → 리뷰 → QA를 순서대로 실행한다. 단계마다 지정된 모델의 서브에이전트로 돌리고, 사람이 정해야 하는 지점이나 실패가 나오면 멈춘다. PR은 만들지 않는다. QA가 끝나면 훅이 실행 내용과 이슈 요약 보고서를 만든다. 사용자가 명시적으로 /gf-run을 호출할 때만 사용.
disable-model-invocation: true
argument-hint: "NN [--from implement|review|qa]"
---

# /gf-run — 구현부터 QA까지 자동 실행

입력: `$ARGUMENTS` — 명세 번호(필수), 선택적으로 `--from <단계>` (이전 실행이 멈춘 단계부터 재개)

## 이 스킬이 하지 않는 것
- **명세 작성과 승인(G1)**: `/gf-spec`으로 쓰고 팀원이 `APPROVED`로 바꾼 뒤에만 시작한다.
- **PR 생성**: QA까지 끝나면 멈춘다. 사용자가 요약 보고서를 확인한 뒤 직접 `/gf-ship`을 실행한다.
- **머지(G5)**: 팀원 승인 후 작성자가 Rebase and merge (D-41).
- **결정 대리**: 서브에이전트가 `NEEDS_DECISION`을 반환하면 대신 정하지 않고 사용자에게 넘긴다.

## 단계와 모델

| 순서 | 단계 | 실행 스킬 | 모델 · effort |
|---|---|---|---|
| 1 | implement | `gf-run-implement` → `gf-implement` 절차 | Sonnet 5.5 · high |
| 2 | review | `gf-run-review` → `gf-review` 절차 | Opus 5.5 · medium |
| 3 | qa | `gf-run-qa` → `gf-qa` 절차 | Sonnet 5.5 · medium |

각 단계는 `context: fork` 서브에이전트로 실행된다. 스킬 안에서 다른 스킬을 바로 호출하면 지정 모델이 적용되지 않기 때문이다(`docs/PIPELINE.md` §8).

## 절차

### 0. 사전 확인 (여기서 실패하면 시작하지 않는다)
- `docs/specs/NN-*.md`를 찾는다. 없으면 `/gf-spec NN`을 안내하고 멈춘다.
- 명세 상태가 `APPROVED` 또는 `IN_PROGRESS`가 아니면 멈춘다(G1).
- `git status`가 깨끗하지 않고 현재 브랜치가 이 명세의 브랜치가 아니면 멈춘다(다른 작업이 섞이지 않게).
- `--from`이 있으면 그 단계부터, 없으면 1단계부터.
- 시작 전에 사용자에게 한 줄로 알린다: "NN-<이름>: implement → review → qa 순서로 실행합니다. 결정이 필요하거나 실패하면 멈춥니다. QA가 끝나면 요약 보고서가 만들어집니다."

### 1~3. 단계 실행 (순서대로, 한 번에 하나)
각 단계마다:
1. Skill 도구로 해당 `gf-run-<단계>` 스킬을 호출한다. 인자는 `NN` + 이전 단계의 `SUMMARY`와 `CONCERNS`(있으면).
2. 반환 블록에서 `STATUS`, `HEAD`, `TESTS`, `DECISIONS_NEEDED`, `CONCERNS`를 읽는다. 형식이 없거나 깨졌으면 `BLOCKED`로 취급한다.
3. **서브에이전트의 보고는 주장일 뿐이다.** 다음 단계로 가기 전에 직접 확인한다.
   - `git log --oneline -3`과 `git status --short`로 HEAD와 커밋되지 않은 변경을 확인한다.
   - implement 이후: 명세의 수용 기준 체크 상태를 읽는다.
   - review 이후: 명세의 `## 리뷰 기록` 마지막 줄이 이번 HEAD로 추가됐는지 확인한다.
   - qa 이후: 명세의 `## QA 기록` 마지막 줄을 확인한다.
4. 진행 판단:

| STATUS | 처리 |
|---|---|
| `DONE` | 다음 단계로 |
| `DONE_WITH_CONCERNS` | 다음 단계로. 단 review에서 미해결 CRITICAL이 있으면 멈춤 |
| `NEEDS_DECISION` | **멈춘다.** `DECISIONS_NEEDED`를 하나씩 사용자에게 보여주고(추천 포함), 결정한 뒤 `/gf-run NN --from <단계>`로 재개하라고 안내한다 |
| `BLOCKED` | **멈춘다.** 막힌 이유를 보여주고, 테스트 실패나 원인 불명이면 `/gf-investigate`를 안내한다 |
| `NEEDS_CONTEXT` | **멈춘다.** 무엇을 모르는지 보여주고, 결정 변경이 필요하면 `docs/DECISIONS.md` 수정이 먼저라고 안내한다 |

5. 같은 단계를 이 실행 안에서 다시 돌리지 않는다(무한 반복 방지). 재시도는 사용자가 `--from`으로 한다.

### 4. 마무리
- QA 단계가 끝나면 `.claude/hooks/gf-run-report.py` 훅이 자동으로 요약 보고서를 `.gf-run/reports/`에 만들고 경로를 알려준다. **보고서를 직접 다시 쓰지 않는다.**
- 보고서 경로를 사용자에게 보여주고, 아래 형식으로 짧게 마무리한다.
```
GF-RUN NN-<이름>
  implement  DONE                 (HEAD abc1234)
  review     DONE_WITH_CONCERNS   (CRITICAL 0, INFO 2)
  qa         DONE                 (수용 기준 n/n)
보고서: .gf-run/reports/<파일>.md
다음: 보고서 확인 → /gf-ship (PR 생성) → 팀원 승인 → Rebase and merge
```
- 중간에 멈췄다면 멈춘 단계, 이유, 재개 명령(`/gf-run NN --from <단계>`)을 같은 형식으로 보여준다. 이 경우 보고서는 만들어지지 않는다.
