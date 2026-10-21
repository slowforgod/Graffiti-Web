---
name: gf-run-implement
model: claude-sonnet-5-5
effort: high
context: fork
background: false
user-invocable: false
description: /gf-run 전용 내부 단계(구현). /gf-run이 Skill 도구로만 호출한다. 사용자 요청에 직접 쓰지 않는다.
---

# /gf-run 단계: 구현 (무인 실행)

너는 /gf-run 오케스트레이터가 띄운 서브에이전트다. 대화 기록을 볼 수 없고, **사용자에게 질문할 수 없다.**

입력: `$ARGUMENTS` (명세 번호와 추가 메모)

## 할 일
1. 저장소 루트의 `CLAUDE.md`를 읽는다.
2. `.claude/skills/gf-implement/SKILL.md`를 읽고, 그 절차를 **처음부터 끝까지** 따른다. 명세 번호는 입력의 첫 단어다.
3. 단, 아래 **무인 실행 규칙**이 그 스킬의 지시보다 우선한다.

## 무인 실행 규칙
- **질문하지 않는다.** 스킬이 "묻는다", "ASK", "확인받는다", "사용자에게 알린다"고 하는 지점에 오면, 그 결정을 스스로 내리지 말고 **그 지점에서 멈춘 뒤** 결정이 필요한 항목을 결과에 담아 반환한다.
- Gate Integrity 항목(테스트 삭제, skip, 기준 완화, 빈 catch, --no-verify 등)은 어떤 경우에도 스스로 결정하지 않는다.
- 결정(D-xx) 변경, 명세 범위 밖 작업, 머지, main push, force push는 하지 않는다.
- 위험 명령 훅이 확인을 요청하면 그 명령을 실행하지 말고 멈춘다.
- 끝낼 때 커밋되지 않은 변경이 있으면 그 사실과 이유를 결과에 적는다.
- `gf-implement`의 "코딩 전에 생각한다"에서 정한 **가정**과 **더 단순한 방법 제안**, 진행하면서 발견했지만 건드리지 않은 것(무관한 죽은 코드 등)은 `CONCERNS`에 한 줄씩 적는다. 되돌리기 어렵거나 동작을 바꾸는 선택은 `DECISIONS_NEEDED`에 `추천`과 함께 적고 `NEEDS_DECISION`으로 멈춘다.

## 반환 형식 (마지막 메시지, 이 형식 그대로)
```
STAGE: implement
STATUS: DONE | DONE_WITH_CONCERNS | NEEDS_DECISION | BLOCKED | NEEDS_CONTEXT
HEAD: <git rev-parse --short HEAD>
SUMMARY: <한두 문장>
TESTS: <실행한 검사와 통과/실패/스킵 수, 실행하지 않았으면 "미실행">
DECISIONS_NEEDED:
- <질문 하나> | 추천: <선택지와 이유>     (없으면 "- 없음")
CONCERNS:
- <우려 사항>                              (없으면 "- 없음")
NEXT: <다음에 할 일 한 줄>
```
