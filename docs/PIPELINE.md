# 개발 파이프라인

> 사람용 설명서입니다. 기능 하나가 **명세 → 구현 → 리뷰 → QA → PR → 배포**까지 어떻게 흘러가는지, 단계마다 무엇이 통과 조건(게이트)인지 정리합니다.
> Claude Code가 따르는 실제 절차는 `.claude/skills/gf-*/SKILL.md`에 있고, 항상 지킬 규칙은 `CLAUDE.md`에 있습니다.
> 출처: gstack 하네스를 이 프로젝트에 맞게 증류함 (`docs/harness/GSTACK_HARNESS.md`, D-26)

---

## 1. 전체 흐름

```
 ┌──────────────────────────── 기능 하나 (브랜치 feat/NN-<이름>) ──────────────────────────────┐
 │                                                                                         │
 │  ① /gf-spec NN      docs/specs/NN-<이름>.md 작성 (계획 잠금 포함)                        │
 │        │            ─ 관련 D-xx / INV-xx / SEC-xx, 수용 기준, 실패 모드, 테스트 계획        │
 │        ▼  [G1] 팀원이 명세를 읽고 상태를 APPROVED로 변경                                   │
 │  ② /gf-implement NN  테스트 먼저(TDD), 명세 범위만, 수직 슬라이스, bisect 커밋               │
 │        ▼  [G2] 빌드 + 전체 테스트 통과 (출력 첨부)                                        │
 │  ③ /gf-review       diff 리뷰: CRITICAL(INV, SEC, 경쟁 조건, LLM 출력, Gate Integrity)     │
 │        │            AUTO-FIX는 바로 수정, ASK는 사람에게 질문                               │
 │        ▼  [G3] 미해결 CRITICAL 0개, 명세에 리뷰 기록                                      │
 │  ④ /gf-qa           통합 시나리오(STOMP 다중 클라이언트) + 브라우저 체크리스트               │
 │        │            버그: 회귀 테스트 먼저(red) → 최소 수정(green) → 커밋                   │
 │        ▼  [G4] 수용 기준 전부 통과, 명세에 QA 기록                                        │
 │  ⑤ /gf-ship         base 머지 → 전체 테스트 → G3/G4 기록 확인 → 문서 동기화 확인 → PR        │
 │        ▼  [G5] 팀원 1명 이상 PR 승인 → main 머지                                         │
 │  ⑥ GitHub Actions   test → build → EC2 배포 (12-deploy 이후)                             │
 │        ▼  (보안 관련 변경이면 배포 전 /gf-security)                                       │
 │  ⑦ /gf-deploy-check 헬스체크, WebSocket 연결, 스모크 테스트                                │
 │        ▼  [G6] 통과 → 명세 상태를 DONE으로                                               │
 └─────────────────────────────────────────────────────────────────────────────────────────┘

 자동 실행: G1 승인 후 /gf-run NN 하나로 ②~④를 연달아 실행 → 요약 보고서 확인 → ⑤는 사람이 직접 (§8)
 언제든:  버그·이상 동작 → /gf-investigate (근본 원인 먼저, 3-strike)
          작업 넘기기/이어받기 → /gf-handoff save | load
          위험 명령(rm -rf, DROP, force-push…) → .claude/hooks/check-careful.sh가 자동으로 확인 요청
          결정을 바꿔야 할 때 → 구현 중단 → docs/DECISIONS.md 수정(팀 합의) → 명세 수정 → 재개
```

## 2. 게이트 정의

| 게이트 | 통과 조건 | 누가 확인 | 기록 위치 |
|---|---|---|---|
| **G1 명세 승인** | 명세의 모든 필수 칸이 채워짐, 관련 D/INV/SEC 인용, 수용 기준이 테스트 가능 | **팀원(사람)** | 명세 상단 `상태: APPROVED` + 승인자 |
| **G2 테스트 통과** | `./gradlew test`, `npm test`, `npm run lint`가 모두 통과. 실제 실행 출력 첨부 | Claude Code (자기 보고는 주장일 뿐이므로 G3에서 다시 실행) | 커밋, PR 본문 |
| **G3 리뷰 통과** | 미해결 CRITICAL 0개. Gate Integrity 항목은 전부 사람이 판단 | `/gf-review` + 사람(ASK 항목) | 명세 `## 리뷰 기록` (커밋 SHA 포함) |
| **G4 QA 통과** | 명세의 수용 기준과 QA 시나리오 전부 통과. 발견된 버그는 회귀 테스트와 함께 수정 또는 TODOS로 | `/gf-qa` | 명세 `## QA 기록` (커밋 SHA 포함) |
| **G5 PR 승인** | 팀원 1명 이상 승인, CI 통과 → **승인받은 PR 작성자가 Rebase and merge** | **팀원(사람)** | GitHub PR |
| **G6 배포 확인** | 헬스체크, WebSocket 연결, 스모크 시나리오 통과 | `/gf-deploy-check` | 명세 `## 배포 기록` |

- **G3, G4 기록에는 커밋 SHA를 남깁니다.** `/gf-ship`은 기록된 SHA 이후에 코드가 바뀌었으면 해당 게이트를 다시 요구합니다.
- **보안 게이트**: 인증(`auth/`), 권한(`config/Security*`), 모더레이션(`moderation/`), 실시간 인터셉터(`realtime/`), ML 서버(`ml/serving/`)를 바꾼 PR은 배포 전에 `/gf-security`를 통과해야 합니다.

## 3. 명세 상태

```
DRAFT ──(G1 사람 승인)──▶ APPROVED ──(구현 시작)──▶ IN_PROGRESS ──(G5 머지)──▶ MERGED ──(G6)──▶ DONE
   ▲                                                    │
   └──────────── 결정 변경이 필요하면 되돌림 ──────────────┘   (BLOCKED: 외부 요인으로 진행 불가)
```

## 4. 기능 로드맵 (명세 순서)

수직 슬라이스 단위입니다. 각 명세는 `/gf-spec NN`으로 작성합니다.

| NN | 이름 | 내용 | 선행 | 관련 결정 |
|---|---|---|---|---|
| 00 | bootstrap | 저장소 골격(backend, frontend), Docker Compose(MySQL), Flyway, `GraffitiProperties`, 에러 응답 형식, CI(test + lint + 시크릿 스캔), `.env.example`, `/actuator/health` | — | D-02, INV-10 |
| 01 | auth | Google OAuth, 도메인 3중 검증, Spring Session JDBC, 동의, 익명 닉네임, `/api/me`, 관리자 지정 | 00 | D-21~D-23, SEC-03/04/13 |
| 02 | page-core | `PageCommandProcessor`, `OccupancyGrid`, `PageLifecycle`, 타이머, 재시작 복구 (백엔드 전용, 순수 로직 위주) | 00 | D-03, D-14, D-15, D-25, INV-01~04/11/12 |
| 03 | canvas-read | `/api/pages/current`, 청크 조회, 미니맵 API / Konva 뷰포트(팬, 줌), 청크 레이어, 미니맵 UI | 01, 02 | D-05, D-06, D-08, D-09 |
| 04 | realtime | STOMP 설정, `ChannelInterceptor`(인증, 구독 검증, 속도 제한), 커서, 구독→조회→병합 | 03 | D-09, INV-09, SEC-07/08 |
| 05 | draft | 툴바, 펜, 텍스트, 지우개(초안), ✓/✗ UI, 초안 중계, 10분 자동 취소 | 04 | D-07, D-10, D-11, D-33 |
| 06 | publish | 게시 REST, `DoodleValidator`, 쿨다운, 점유율 갱신, 브로드캐스트 (모더레이션은 항상 통과하는 임시 구현) | 05 | D-12, D-27, D-28, INV-05/08 |
| 07 | moderation | 금칙어, ChatKHU, OpenAI, `DoodleRenderer`, REJECTED/UNCHECKED, 타임아웃 | 06 | D-18, D-19, SEC-05/15 |
| 07b | ml-moderation | `ml/serving`(FastAPI, CLIP 제로샷 이미지 분류 + OCR), `MlModerationProvider`, 병렬 호출, OCR 글자 → 텍스트 검사, `ml_prediction` 기록, **SHADOW 모드**, 평가 스크립트 | 07 (ML 서버 자체는 00 이후 병행 가능) | D-34~D-40, INV-13, SEC-17 |
| 08 | my-doodles | 내 doodle 삭제, 선택 삭제, PNG 저장 | 06 | D-13 |
| 09 | page-turn-ux | 상단 상태바, T−5분 카운트다운과 경고, `page:turned` 처리, 초안 폐기 | 04, 06 | D-16 |
| 10 | admin | 관리자 API와 화면, 차단, 복구, 조치 기록 | 07 | D-23, SEC-14 |
| 11 | security-pass | `/gf-security` 전체 감사와 수정, 속도 제한 마무리 | 01~10 | SEC 전체 |
| 12 | deploy | EC2, nginx, TLS, Actions 배포, 백업, 복구 리허설 | 00 (언제든 병행 가능) | D-02, D-04, SEC-10/11 |
| 12b | production-backup | 스테이징 서버를 운영으로 승격, 매일 백업과 복구 리허설(SEC-11), 로그 로테이션, GHCR 정리, 공개 전 점검표 | 12a, 12c | D-01, D-04, D-24, D-25, SEC-10/11/13 |
| 12c | aws-infra | 스테이징 EC2, 보안 그룹, Elastic IP를 CDK(`infra/`)로 정의. 도메인, 인증서, 비밀값은 제외 | 12a | D-02, D-04, D-36, SEC-10 |

```
00 ─┬─ 01 ─┐
    ├─ 02 ─┴─ 03 ── 04 ── 05 ── 06 ─┬─ 07 ─┬─ 07b ─┐
    │                               │      └─ 10 ──┼─ 11
    │                               ├─ 08          │
    │                               └─ 09 ─────────┘
    ├─ ml-prep (병행): 평가 세트 그리기, ml/serving 단독 개발, 제로샷 카테고리 문장 튜닝
    └─ 12 (병행)
```

- **병렬로 가능한 작업**: 01과 02, 07·08·09, 12, 그리고 **ML 준비 작업**. 서로 다른 팀원이 다른 브랜치로 진행할 수 있습니다.
- **ML 파트는 일찍 시작할 수 있습니다.** `ml/serving`은 PNG를 받아 판정만 돌려주는 독립 서버라, 백엔드 없이도 팀 평가 세트(D-37)로 개발하고 평가할 수 있습니다. Spring과의 연결은 07b에서 합니다.
- **07b 이후의 ML 작업**(미세조정, ENFORCE 전환)은 운영 데이터가 쌓인 뒤 진행합니다. ENFORCE 전환은 D-39 기준 충족 + 팀 승인이 게이트입니다(TODOS).
- 06에서 모더레이션을 "항상 통과" 임시 구현으로 두는 이유: 게시 흐름을 먼저 끝까지 연결해 두고, 07에서 `ModerationProvider` 구현만 갈아끼우기 위해서입니다. 단, **06만 배포하는 일은 없어야 합니다**(INV-05 위반 상태).

## 5. 팀 운영 규칙

- **브랜치**: `feat/NN-<이름>`, `fix/<짧은-설명>`, `docs/<짧은-설명>`. main에 직접 push 금지
- **PR과 머지**: PR은 작업 브랜치에서 main으로 연다. G1~G4는 `/gf-ship`이 확인하고, **팀원 1명 이상 승인 + CI 통과(G5) 후 PR 작성자가 직접 머지**한다. Claude Code는 머지하지 않는다
- **머지 방식은 "Rebase and merge"**: bisect 가능하게 나눈 커밋을 main에 그대로 남기기 위해서다. "Squash and merge"는 쓰지 않는다. 머지 후 작업 브랜치는 삭제한다
- **브랜치 보호**: 비공개 저장소에서 main 보호 규칙(승인·CI 필수, 직접 push 금지)을 GitHub이 강제하려면 Pro 이상(학생은 Student Developer Pack)이 필요하다. 보호 규칙이 없는 동안에는 이 규칙과 훅(main push 확인)으로 지킨다
- **작업 넘기기**: 하루 작업이 끝나거나 다른 팀원에게 넘길 때 `/gf-handoff save` → `.handoff/<브랜치>.md`를 커밋. 이어받는 사람은 `/gf-handoff load`
- **결정 변경**: 구현 중 결정과 맞지 않는 상황을 만나면 Claude Code는 `NEEDS_CONTEXT`로 멈춥니다. 팀이 `docs/DECISIONS.md`를 고친 뒤(번복은 SUPERSEDED 표시) 명세를 수정하고 재개합니다
- **범위 밖 아이디어**: 코드에 넣지 말고 `TODOS.md`에 추가

## 6. gstack 원본과의 대응

| 이 프로젝트 | gstack 원본 | 바꾼 점 |
|---|---|---|
| `/gf-spec` | `/spec` + `/plan-eng-review` | 아키텍처는 이미 잠겼으므로 기능별 계획 잠금을 명세 안에 흡수 |
| `/gf-implement` | (없음) | gstack에는 구현 전용 스킬이 없음. 단계별 모델 지정을 위해 추가 |
| `/gf-review` | `/review` | CRITICAL 체크리스트를 INV/SEC 기반으로 교체, Greptile·Codex 제거 |
| `/gf-qa` | `/qa` | 브라우저 중심 → STOMP 다중 클라이언트 통합 시나리오 중심 |
| `/gf-investigate` | `/investigate` | freeze 훅 대신 프롬프트 규칙으로 범위 고정 |
| `/gf-security` | `/cso` | OWASP 일반론 대신 SEC-01~17 점검, 배포 전 게이트로 |
| `/gf-ship` | `/ship` | VERSION·CHANGELOG·eval 제거, 문서 동기화와 게이트 기록 확인 추가 |
| `/gf-deploy-check` | `/land-and-deploy` + `/canary` | 머지와 배포는 GitHub Actions가 하고, 배포 후 확인만 |
| `/gf-handoff` | `/context-save` + `/context-restore` | 팀원 간 공유를 위해 저장소에 커밋 |
| `check-careful.sh` 훅 | `/careful` | 스킬 호출 없이 항상 켜짐 |
| (제외) | `/office-hours`, `/plan-ceo-review`, `/autoplan`, `/retro` 등 | 설계 인터뷰로 이미 대체됐거나 MVP에 불필요 |

## 7. 단계별 모델과 effort

각 스킬의 `SKILL.md` frontmatter(`model`, `effort`)에 지정되어 있습니다. **사용자가 슬래시 명령으로 직접 호출할 때** 그 스킬이 끝날 때까지 적용되고, 다음 프롬프트부터는 세션에서 고른 모델로 돌아갑니다. 다른 스킬 안에서 이어 호출하면 적용되지 않으므로, 자동 실행(`/gf-run`)은 단계마다 서브에이전트를 씁니다(§8).

| 단계 | 스킬 | 모델 | effort | 이유 |
|---|---|---|---|---|
| ① 명세 | `/gf-spec` | Opus 5.5 | medium | 설계 판단과 실패 모드 도출 |
| ② 구현 | `/gf-implement` | Sonnet 5.5 | high | 코드 작성량이 가장 많은 단계 |
| ③ 리뷰 | `/gf-review` | Opus 5.5 | medium | 경쟁 조건, 우회 경로 탐지 |
| 디버깅 | `/gf-investigate` | Opus 5.5 | high | 근본 원인 추론이 가장 어려움 |
| 보안 감사 | `/gf-security` | Opus 5.5 | medium | 증거 기반 판정 |
| ④ QA | `/gf-qa` | Sonnet 5.5 | medium | 실행과 확인의 반복 |
| ⑤ PR | `/gf-ship` | Sonnet 5.5 | low | 정해진 절차 확인 |
| ⑦ 배포 확인, 인수인계 | `/gf-deploy-check`, `/gf-handoff` | Haiku 5.5 | medium | 읽기 전용 점검과 요약 |

- 모델 ID: `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`
- 사용할 수 있는 effort 단계는 모델마다 다를 수 있습니다. 지정한 단계를 모델이 지원하지 않으면 Claude Code의 기본 결정 순서를 따릅니다.
- 바꿀 때는 해당 `SKILL.md`의 frontmatter와 이 표를 같은 커밋에서 고칩니다.

## 8. 자동 실행 (`/gf-run`)

```
사람: /gf-spec NN → 팀원 검토 → APPROVED (G1)
/gf-run NN ─▶ gf-run-implement ─▶ gf-run-review ─▶ gf-run-qa ─▶ 정지
              (Sonnet · high)     (Opus · medium)   (Sonnet · medium)
                                                          │
                              훅 gf-run-report.py ◀───────┘  .gf-run/reports/<브랜치>-<시각>.md
사람: 보고서 확인 → /gf-ship (PR 생성) → 팀원 승인 → Rebase and merge (G5)
```

- `gf-run-*`는 `/gf-run` 전용 래퍼 스킬입니다. `context: fork`로 **단계마다 별도 서브에이전트**를 띄우고, 원래 스킬(`gf-implement` 등)의 절차를 무인 실행 규칙과 함께 따릅니다. 슬래시 메뉴에는 보이지 않습니다(`user-invocable: false`).
- **멈추는 조건**: 서브에이전트가 `NEEDS_DECISION`(리뷰의 ASK 항목, Gate Integrity 등) / `BLOCKED`(테스트 실패, 환경 문제) / `NEEDS_CONTEXT`(결정 변경 필요)를 반환하면 정지합니다. 사용자가 결정한 뒤 `/gf-run NN --from <단계>`로 재개합니다.
- **검증**: 오케스트레이터는 서브에이전트 보고를 그대로 믿지 않고 git 상태와 명세의 리뷰·QA 기록을 직접 확인한 뒤 다음 단계로 넘어갑니다.
- **PR은 자동으로 만들지 않습니다.** QA까지 끝나면 멈추고, 사용자가 보고서를 확인한 뒤 `/gf-ship`을 직접 실행합니다.
- 단계별 모델을 바꿀 때는 원래 스킬과 `gf-run-*` 래퍼의 frontmatter를 **함께** 고칩니다(§7 표와 같은 커밋).

### QA 완료 요약 보고서 (훅)
- `.claude/settings.json`의 `PostToolUse`(matcher `Skill`) 훅이 `.claude/hooks/gf-run-report.py`를 실행합니다.
- `gf-run-*` 단계가 끝날 때마다 반환 결과를 `.gf-run/<브랜치>.jsonl`에 쌓고, **`gf-run-qa`가 끝나면** 이번 실행(마지막 implement 이후)의 보고서를 `.gf-run/reports/<브랜치>-<시각>.md`로 만듭니다.
- 보고서 내용: 단계별 상태·요약·테스트, **확인이 필요한 이슈**(DONE이 아닌 단계, 결정 필요 항목, 우려), QA에서 다룬 이슈 ID, 커밋 목록, 변경 파일, 커밋되지 않은 변경, 명세의 리뷰·QA 기록, 다음 할 일, 단계별 원본 결과
- 만들어지면 채팅에 경로와 이슈 건수가 표시되고, macOS 알림도 뜹니다.
- `.gf-run/`은 로컬 전용이라 커밋되지 않습니다(`.gitignore`). 중간에 멈춘 실행은 보고서를 만들지 않습니다.
- 훅은 어떤 오류가 나도 파이프라인을 막지 않습니다. 오류는 `.gf-run/hook-error.log`에 남습니다.

### 모델 적용 실험 (2026-10-21, Claude Code 2.1.299)
| 방식 | 결과 |
|---|---|
| 사용자가 `/probe-a`(model: haiku) 직접 호출 | Haiku로 실행됨 ✅ |
| 오케스트레이터 스킬이 `probe-a`를 Skill 도구로 이어 호출 (inline) | 지정 모델 무시, 세션 모델(Opus)로 실행됨 ❌ |
| 오케스트레이터가 `context: fork` + `background: false`인 `probe-a`, `probe-b`를 이어 호출 | Haiku, Sonnet이 각각 사용됨, 순서대로 끝까지 실행됨 ✅ |
| `PostToolUse`(matcher `Skill`) 훅이 fork 스킬 종료 시 받는 입력 | `tool_input.skill`에 스킬 이름, `tool_response.result`에 서브에이전트 반환 텍스트 ✅, 훅의 `systemMessage`가 사용자에게 표시됨 ✅ |

