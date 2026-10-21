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
 │  ② 구현 (Claude Code)  테스트 먼저(TDD), 명세 범위만, 수직 슬라이스, bisect 커밋              │
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
| **G5 PR 승인** | 팀원 1명 이상 승인, CI 통과 | **팀원(사람)** | GitHub PR |
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
| 07 | moderation | 금칙어, KHUChat, OpenAI, `DoodleRenderer`, REJECTED/UNCHECKED, 타임아웃 | 06 | D-18, D-19, SEC-05/15 |
| 07b | ml-moderation | `ml/serving`(FastAPI, CLIP 제로샷 이미지 분류 + OCR), `MlModerationProvider`, 병렬 호출, OCR 글자 → 텍스트 검사, `ml_prediction` 기록, **SHADOW 모드**, 평가 스크립트 | 07 (ML 서버 자체는 00 이후 병행 가능) | D-34~D-40, INV-13, SEC-17 |
| 08 | my-doodles | 내 doodle 삭제, 선택 삭제, PNG 저장 | 06 | D-13 |
| 09 | page-turn-ux | 상단 상태바, T−5분 카운트다운과 경고, `page:turned` 처리, 초안 폐기 | 04, 06 | D-16 |
| 10 | admin | 관리자 API와 화면, 차단, 복구, 조치 기록 | 07 | D-23, SEC-14 |
| 11 | security-pass | `/gf-security` 전체 감사와 수정, 속도 제한 마무리 | 01~10 | SEC 전체 |
| 12 | deploy | EC2, nginx, TLS, Actions 배포, 백업, 복구 리허설 | 00 (언제든 병행 가능) | D-02, D-04, SEC-10/11 |

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

- **브랜치**: `feat/NN-<이름>`, `fix/<짧은-설명>`. main에 직접 push 금지
- **작업 넘기기**: 하루 작업이 끝나거나 다른 팀원에게 넘길 때 `/gf-handoff save` → `.handoff/<브랜치>.md`를 커밋. 이어받는 사람은 `/gf-handoff load`
- **결정 변경**: 구현 중 결정과 맞지 않는 상황을 만나면 Claude Code는 `NEEDS_CONTEXT`로 멈춥니다. 팀이 `docs/DECISIONS.md`를 고친 뒤(번복은 SUPERSEDED 표시) 명세를 수정하고 재개합니다
- **범위 밖 아이디어**: 코드에 넣지 말고 `TODOS.md`에 추가

## 6. gstack 원본과의 대응

| 이 프로젝트 | gstack 원본 | 바꾼 점 |
|---|---|---|
| `/gf-spec` | `/spec` + `/plan-eng-review` | 아키텍처는 이미 잠겼으므로 기능별 계획 잠금을 명세 안에 흡수 |
| `/gf-review` | `/review` | CRITICAL 체크리스트를 INV/SEC 기반으로 교체, Greptile·Codex 제거 |
| `/gf-qa` | `/qa` | 브라우저 중심 → STOMP 다중 클라이언트 통합 시나리오 중심 |
| `/gf-investigate` | `/investigate` | freeze 훅 대신 프롬프트 규칙으로 범위 고정 |
| `/gf-security` | `/cso` | OWASP 일반론 대신 SEC-01~17 점검, 배포 전 게이트로 |
| `/gf-ship` | `/ship` | VERSION·CHANGELOG·eval 제거, 문서 동기화와 게이트 기록 확인 추가 |
| `/gf-deploy-check` | `/land-and-deploy` + `/canary` | 머지와 배포는 GitHub Actions가 하고, 배포 후 확인만 |
| `/gf-handoff` | `/context-save` + `/context-restore` | 팀원 간 공유를 위해 저장소에 커밋 |
| `check-careful.sh` 훅 | `/careful` | 스킬 호출 없이 항상 켜짐 |
| (제외) | `/office-hours`, `/plan-ceo-review`, `/autoplan`, `/retro` 등 | 설계 인터뷰로 이미 대체됐거나 MVP에 불필요 |
