---
name: gf-ship
model: claude-sonnet-5-5
effort: low
description: graffiti PR 생성 파이프라인. base 브랜치 머지 → 전체 테스트 → 리뷰·QA 기록 확인(G3, G4) → 문서 동기화 확인 → bisect 커밋 정리 → push → PR 생성. 사용자가 명시적으로 /gf-ship을 호출할 때만 사용.
disable-model-invocation: true
---

# /gf-ship — PR까지

## 절대 규칙
- **force push 금지.** `git push --force`, `--force-with-lease` 모두 쓰지 않는다.
- **실패한 테스트를 커밋하거나 push하지 않는다.**
- **`git add -A`, `git add .` 금지.** 파일을 지정해서 stage한다.
- **main에 직접 push하지 않는다.** PR만 만든다. 머지는 사람이 한다(G5).
- 게이트를 건너뛰지 않는다. 막히면 멈추고 보고한다.

## 단계

### 1. 사전 확인
- 현재 브랜치가 `main`이면 중단한다.
- `git status`로 변경 사항을 확인한다. `.env`, 빌드 산출물(`build/`, `dist/`, `node_modules/`), 모델 가중치(`*.pt`, `*.onnx`, `*.safetensors`, `*.bin`), 학습 이미지나 데이터 추출본이 보이면 중단하고 알린다.
- 관련 명세 `docs/specs/NN-*.md`를 찾는다. 상태가 `APPROVED` 또는 `IN_PROGRESS`가 아니면 중단한다(G1).

### 2. base 머지 (테스트 전에)
- `git fetch origin main && git merge origin/main`
- 충돌이 나면 해결 방향을 사용자와 확인한다. 생성 파일이나 마이그레이션 번호 충돌에 주의한다.

### 3. 전체 테스트 (머지된 코드로)
- `CLAUDE.md` §2의 전체 검사 명령을 실행한다(백엔드 테스트, 프론트 테스트, 린트, 빌드).
- 실패하면 중단한다. 원인이 이번 변경이 아니라고 주장하려면 **main에서도 실패하는 것을 보여줘야** 한다. 보여주지 못하면 "미확인"이라고 쓴다.
- 결과(통과/실패/스킵 수)를 기록해 둔다.

### 4. 게이트 기록 확인
- 명세의 `## 리뷰 기록` 마지막 줄: 상태가 DONE 또는 DONE_WITH_CONCERNS이고, 기록된 SHA 이후의 변경이 **리뷰 후 수정(AUTO-FIX, ASK 승인)뿐인지** 확인한다. 그 밖의 코드 변경이 있으면 `/gf-review`를 다시 요구한다(G3).
- `## QA 기록` 마지막 줄도 같은 방식으로 확인한다(G4).
- 보안 영역(`auth/`, `config/Security*`, `moderation/`, `realtime/`, `ml/serving/`, `docker-compose.yml`)이 바뀌었으면 PR 본문에 **"배포 전 /gf-security 필요"**를 표시한다.

### 5. 문서 동기화 (같은 PR 안에 있어야 함)
| 변경 | 같이 바뀌어야 할 문서 |
|---|---|
| 컨트롤러 경로, 요청/응답 필드, STOMP 목적지, 메시지 타입, 에러 코드 | `docs/API.md` |
| ML 서버 요청·응답 스키마 | `docs/API.md` §7 + `MlModerationProvider` |
| 엔티티, 마이그레이션 | `docs/DB.md` |
| 큐 흐름, 상태 전이, 새 컴포넌트 | `docs/ARCHITECTURE.md` (다이어그램 포함) |
| 설정값 추가 | `ARCHITECTURE.md` §6 표 |
| 결정과 다르게 구현해야 했던 것 | `docs/DECISIONS.md` (팀 합의 필요 → 없으면 중단) |
| 범위에서 미룬 것 | `TODOS.md` |
- 누락이 있으면 문서를 갱신하고 사용자에게 보여준다. 결정 변경은 스스로 하지 않는다.

### 6. 커밋 정리 (bisect 가능하게)
- 커밋 하나에 논리적 변경 하나: 이름 변경·이동 / 리팩터링 / 기능 / 테스트 인프라 / 문서를 분리한다.
- 메시지는 Conventional Commits: `feat(publish): ...`, `fix(page): ...`, `test(...)`, `docs(...)`, `refactor(...)`
- 미커밋 변경을 커밋한 뒤, 리뷰 기록이 `+dirty`였다면 실제로 커밋된 내용이 리뷰된 내용과 같은지 확인한다.
- 각 커밋이 단독으로 빌드되는지 의심되면 사용자에게 알린다.

### 7. 최종 확인과 push
- 코드를 더 이상 바꾸지 않는 상태에서 전체 테스트를 **한 번 더** 실행한다. 3단계 이후 변경이 없으면 생략할 수 있다(그 사실을 적는다).
- `git push -u origin <브랜치>`

### 8. PR 생성
`gh pr create`로 만든다. 본문 형식:
```
## 무엇을
명세: docs/specs/NN-<이름>.md
(한두 문장 요약)

## 근거
D-xx, INV-xx, SEC-xx

## 검증
- 테스트: 백엔드 n 통과 / 프론트 n 통과 / 실패 0 / 스킵 n (실행 커밋 SHA)
- 리뷰: (리뷰 기록 줄)
- QA: (QA 기록 줄)
- 미검증 항목: (있으면)

## 문서 변경
API.md / DB.md / ARCHITECTURE.md / DECISIONS.md / TODOS.md 중 해당 항목

## 배포 전 필요한 것
(/gf-security 필요 여부, 마이그레이션, 새 환경변수)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
- 명세 상태를 `IN_PROGRESS`로 두고, PR 링크를 명세 상단에 추가한다. MERGED로 바꾸는 건 머지 후 사람이 한다.

## 완료 상태
- `DONE` — PR 생성, 모든 게이트 기록 확인
- `DONE_WITH_CONCERNS` — PR은 생성했으나 미검증 항목이나 보안 감사 필요 표시가 있음
- `BLOCKED` — 테스트 실패, 게이트 기록 누락, 머지 충돌 미해결
