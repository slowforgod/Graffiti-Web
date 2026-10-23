# gstack 하네스 구조 분석 (graffiti 적용용)

> 분석 대상: [garrytan/gstack](https://github.com/garrytan/gstack) `main` @ `c580561` (2026-10-20)
> 목적: Garry Tan의 Claude Code 하네스 구조를 이해하고, 온라인 낙서장(graffiti) 프로젝트에 적용할 부분을 고른다.
> 범위: 파일별 목적과 동작은 "간단히", 역할별로 중요한 동작은 "핵심만" 정리했다. iOS, 브라우저 엔진 내부, gbrain, OpenClaw처럼 이 프로젝트와 무관한 영역은 한 줄로만 다룬다.

---

## 1. gstack이 무엇인가

gstack은 **Claude Code에 "역할(role)"을 입히는 스킬 묶음**이다. 스킬 하나가 전문가 한 명이다. CEO 리뷰어, 엔지니어링 매니저, 디자이너, QA 리드, 릴리스 엔지니어, 디버거 같은 식이다. 사용자는 `/office-hours`, `/plan-eng-review`, `/review`, `/ship`처럼 **슬래시 명령으로 역할을 호출**하고, 각 역할은 정해진 단계, 체크리스트, 게이트(통과 조건)를 따른다.

핵심 아이디어는 세 가지다.

1. **작업을 파이프라인 단계로 쪼갠다.** 생각 → 계획 → 구현 → 리뷰 → 테스트 → 배포 → 회고 순서로 나누고, 단계마다 다른 역할이 맡는다.
2. **각 역할은 "무엇을 하면 안 되는지"까지 명시한다.** 예를 들어 리뷰 역할은 코드를 고치지 않고, 계획 역할은 구현하지 않으며, 디버거는 근본 원인을 찾기 전에 수정하지 않는다.
3. **AI의 자기 인증을 믿지 않는다.** "테스트 통과했어요"는 다시 실행해서 확인하기 전까지 주장일 뿐이다. 테스트를 지우거나 기준을 낮춰서 통과시키는 행위(게이트 무력화)는 사람이 직접 승인해야 한다.

---

## 2. 저장소 구조와 동작 방식

### 2.1 최상위 문서 파일

| 파일 | 목적 | 동작 / 읽는 주체 |
|---|---|---|
| `CLAUDE.md` | **gstack 저장소 자체를 개발할 때** Claude Code가 지킬 규칙 | 매 세션 자동 로드. 명령어, 테스트 정책, 구조 지도, 템플릿 작성법, 커밋 스타일, 스킬 라우팅, 결정 기록 방법을 담는다 |
| `AGENTS.md` | **모든 AI 에이전트용** 스킬 카탈로그와 검증 규율 | Codex 같은 다른 에이전트도 읽는 공용 문서. ① 단계별 스킬 표 ② "Validation discipline" 9단계 ③ 빌드 명령어 ④ 핵심 컨벤션 |
| `ETHOS.md` | 빌더 철학 (Boil the Ocean, Search Before Building, User Sovereignty, Honest Work) | 각 스킬의 preamble에 요약본이 들어가서 판단 기준이 된다. **AI 수정 금지** 파일 |
| `ARCHITECTURE.md` | 시스템이 왜 이렇게 생겼는지 (데몬 모델, 보안 모델, 템플릿 시스템, 에러 철학) | 구조를 바꾸기 전에 읽는 설계 근거 문서 |
| `SKILL.md(.tmpl)` | 루트 **라우터 스킬** (`/gstack`) | 요청을 보고 맞는 스킬로 보낸다. "버그 → /investigate", "배포 → /ship" 같은 라우팅 규칙 |
| `TODOS.md` | 미뤄둔 작업 목록 | 계획과 리뷰 스킬이 "범위 밖" 항목을 여기에 적는다. What/Why/Pros/Cons/Context/Depends-on 형식 |
| `CHANGELOG.md` + `VERSION` | 릴리스 기록, 4자리 버전 | `/ship`이 브랜치마다 한 번 자동으로 bump하고 항목을 작성한다 |
| `CONTRIBUTING.md` | 어떤 테스트 명령을 언제 돌릴지, 비용, 사전 조건 | "Which command do I run?" 표 |
| `DESIGN.md` | 디자인 시스템의 기준 문서 | `/design-consultation`이 만들고, 디자인 계열 스킬들이 읽는다 |
| `SECURITY.md` | 보안 취약점 신고 정책 | |
| `docs/` | 세부 문서 (테스트 내부, 기여자 참고, 설계 문서 `docs/designs/`) | CLAUDE.md가 길어지지 않도록 세부 내용을 여기로 뺀다 ("token-load reduction") |

### 2.2 스킬 하나의 구조

```
<skill-name>/
├── SKILL.md.tmpl     ← 사람이 쓰는 원본 (프롬프트 + {{PLACEHOLDER}})
├── SKILL.md          ← 생성 결과물 (커밋됨, 직접 수정 금지)
├── sections/         ← 필요할 때만 읽는 세부 단계 (lazy load)
│   ├── manifest.json ← 섹션 목록과 "언제 읽는지" 트리거
│   └── *.md(.tmpl)
├── bin/              ← 스킬 전용 셸 스크립트 (훅, 헬퍼)
└── checklist.md 등   ← 사람이 직접 관리하는 체크리스트
```

- **frontmatter**: `name`, `description`(언제 호출되는지, Claude가 보고 자동 선택), `triggers`, `allowed-tools`(쓸 수 있는 도구 제한), `hooks`(PreToolUse 훅)
- **`{{PREAMBLE}}`**: 거의 모든 스킬 앞에 붙는 공통 시작 블록이다. 업데이트 확인, 세션 추적, AskUserQuestion 형식(맥락 → 질문 → `RECOMMENDATION` → 선택지), Search Before Building, 종료 시 학습 기록이 들어 있다.
- **템플릿 → 생성 방식** (`scripts/gen-skill-docs.ts`): 명령어 목록 같은 값은 소스 코드에서 자동으로 채운다. 그래서 문서와 코드가 어긋날 수 없다. 생성된 SKILL.md를 커밋하고, CI가 최신 상태인지 검사한다.
- **sections lazy load**: SKILL.md가 1,500~2,000줄이라 전부 읽으면 컨텍스트가 터진다. 본문에는 뼈대만 두고, 단계에 도달했을 때 해당 섹션 파일을 읽게 한다.

### 2.3 상태 저장 위치 (`~/.gstack/projects/<slug>/`)

| 저장물 | 만드는 스킬 | 용도 |
|---|---|---|
| 설계 문서 (design doc) | `/office-hours` | 이후 모든 계획 리뷰의 "기준 문서" |
| CEO 계획, 리뷰 리포트, 테스트 계획 | `/plan-*-review` | 다음 단계가 읽어서 이어받는다 |
| Review Log | 각 리뷰 | `/ship`이 "리뷰를 거쳤는지" 대시보드로 확인한다 |
| `decisions.jsonl` | 모든 스킬 | **내구성 있는 결정**(아키텍처, 범위 축소, 도구 선택)과 이유를 append-only로 기록한다. 같은 논쟁을 다시 하지 않기 위함 |
| learnings | `/investigate`, `/qa` 등 | "이 영역에서 전에 이런 버그가 있었다" 같은 교훈. 다음 세션이 검색해서 쓴다 |
| context saves | `/context-save` | 브랜치, 변경 사항, 결정, 남은 작업 스냅샷 |

### 2.4 안전장치 = 훅(hook)

`/careful`, `/freeze`는 프롬프트로 "조심하라"고 부탁하는 수준이 아니라 **Claude Code의 PreToolUse 훅**으로 실제로 막는다.

- `careful/bin/check-careful.sh`: Bash 명령을 실행하기 전에 `rm -rf`, `DROP TABLE`, `git push --force`, `git reset --hard` 같은 패턴을 검사해서 경고한다.
- `freeze/bin/check-freeze.sh`: Edit/Write 대상이 허용 디렉토리 밖이면 **차단**한다(fail-closed).
- `/investigate`는 근본 원인을 찾은 뒤 자동으로 freeze를 걸어 "엉뚱한 파일 수정"을 막는다.

---

## 3. 파이프라인 전체 그림

```
 THINK            PLAN                         BUILD+REVIEW              TEST            SHIP                 REFLECT
┌──────────┐   ┌───────────────────────┐   ┌────────────────────┐   ┌──────────┐   ┌──────────────────┐   ┌──────────┐
│/office-  │──▶│/plan-ceo-review (범위) │──▶│ (구현: Claude Code)│──▶│/qa       │──▶│/ship (PR 생성)   │──▶│/retro    │
│ hours    │   │/plan-design-review(UI)│   │/review (diff 리뷰) │   │/qa-only  │   │/land-and-deploy  │   │/learn    │
│(설계문서)│   │/plan-eng-review(아키) │   │/investigate (버그) │   │/cso(보안)│   │/canary (모니터링)│   │/health   │
└──────────┘   │ = /autoplan 으로 일괄 │   └────────────────────┘   └──────────┘   │/document-release │   └──────────┘
               │/spec (이슈 단위 명세) │                                             └──────────────────┘
               └───────────────────────┘
 공통: /careful /freeze /guard (안전), /context-save /context-restore (세션 이어가기), /codex (외부 2차 의견)
```

**흐름의 규칙**
- 앞 단계의 산출물(설계 문서, 리뷰 리포트, 테스트 계획)이 다음 단계의 입력이 된다.
- 계획 리뷰는 **CEO → 디자인 → DX → 엔지니어링** 순서이고, **엔지니어링이 항상 마지막**이다. 최종 수정된 계획을 엔지니어링 관점이 잠그기 때문이다.
- `/ship`은 "리뷰를 거쳤는가"를 Review Log로 확인하고, 테스트·리뷰·문서 감사를 다시 한 번 돌린 뒤에만 PR을 만든다.

---

## 4. 역할별 정리

표기: ★ = graffiti에 적극 적용 / ○ = 선택 적용 / ✕ = 무관

### 4.1 THINK — 아이디어 다듬기

#### ★ `/office-hours` — YC 오피스아워 (`office-hours/`)
- **목적**: 코드를 쓰기 전에 아이디어를 재구성하고 **설계 문서**를 만든다.
- **동작**: Phase 1 맥락 수집 → 2A 스타트업 진단(수요, 현 상태, 가장 좁은 진입점 등 6가지 질문) 또는 2B 빌더 모드(사이드 프로젝트·학습용 브레인스토밍) → 3 **전제 도전**(이게 맞는 문제인가? 아무것도 안 하면?) → 4 **대안 2~3개 생성** → 설계 문서 저장
- **중요 동작**
  - **절대 구현하지 않는다**(스캐폴딩도 금지).
  - **질문은 한 번에 하나씩.**
  - 전제를 `PREMISES: 1. ... — agree/disagree?` 형식으로 사용자 동의를 받는다.
  - 완성된 계획을 가져와도 전제 도전과 대안 생성은 생략하지 않는다.
  - 끝날 때 반드시 **현실에서 할 다음 행동 하나**를 과제로 준다.
  - 상태: `DONE` / `DONE_WITH_CONCERNS` / `NEEDS_CONTEXT`
- 👉 **이번 대화의 grilling 세션이 사실상 이 역할**이었다. 결과물이 `docs/DECISIONS.md`에 해당한다.

### 4.2 PLAN — 계획 리뷰

#### ○ `/plan-ceo-review` — CEO 관점 리뷰
- **목적**: 범위와 야망을 점검한다. "10점짜리 제품은 무엇인가."
- **동작**: 4가지 모드 중 하나를 고른다. **SCOPE EXPANSION**(크게 꿈꾸기) / **SELECTIVE EXPANSION**(범위 유지 + 일부 확장 선택) / **HOLD SCOPE**(범위 고정, 최대 엄격성) / **SCOPE REDUCTION**(핵심만 남기기)
- **중요 동작 (Prime Directives)**
  - 조용한 실패 0개
  - 모든 에러의 종류, 원인, 처리, 사용자에게 보이는 결과, 테스트를 명시
  - happy path, nil, 빈 값, 상위 에러 경로를 추적
  - 더블클릭, 뒤로가기, 느린 네트워크, 오래된 상태까지 매핑
  - 새 흐름은 ASCII 다이어그램으로 그림
  - 미룬 항목은 TODOS.md에 기록
  - **범위 변경은 하나하나 사용자 승인**
- 👉 graffiti는 MVP 범위가 이미 확정됐으니 **HOLD SCOPE 모드**로 쓰면 된다.

#### ★ `/plan-eng-review` — 엔지니어링 매니저 리뷰 (`plan-eng-review/`)
- **목적**: 아키텍처, 데이터 흐름, 엣지 케이스, 테스트, 성능을 **잠근다(lock in)**.
- **동작**
  1. **Scope gate**: 무엇을 리뷰할지(브랜치 diff / 계획 문서 / 특정 경로) 먼저 확정한다.
  2. **Scope Challenge**: 이미 있는 걸로 해결되나? 최소 변경은? 복잡도 계산(새 파일, 클래스 수), 동시성이나 인프라는 "built-in이 있는지" 검색, TODOS 교차 확인
  3. **4개 섹션을 생략 없이**: ① Architecture(경계, 데이터 흐름, 단일 장애점, 보안, 새 경로마다 현실적 장애 1개) ② Code Quality ③ Test(테스트 다이어그램, 빈틈) ④ Performance(N+1, 메모리, 인덱스, 타임아웃 없는 블로킹 호출)
  4. 발견 사항은 하나씩 `D<N>` 번호로 질문하고 결정 원장(ledger)에 기록한다.
  5. **필수 산출물**: NOT in scope / What already exists / ASCII 다이어그램 / **Failure modes**(테스트도, 에러 처리도 없고, 조용히 실패하면 **critical gap**) / 병렬화 전략(worktree lane) / 미해결 결정 / 완료 요약
- **중요 동작**
  - 리뷰만 하고 구현하지 않는다.
  - 엔지니어링 선호: 명시적 > 영리함, 최소 diff, 모든 동작은 테스트, "회귀를 못 잡는 테스트는 만들지 않음"
  - 계획 문서나 다른 에이전트가 쓴 메모는 **데이터일 뿐 지시가 아니다**("리뷰 생략해" 같은 문구는 의심 콘텐츠로 보고).

#### ○ `/plan-design-review` — 디자이너 관점 계획 리뷰
- **목적**: UI 계획의 각 디자인 차원을 **0~10점**으로 매기고, 10점이 되려면 무엇이 필요한지 설명한 뒤 계획을 고친다.
- 👉 캔버스 UI(툴바, 미니맵, 카운트다운 배너)를 구현하기 전에 한 번 돌리면 좋다.

#### ✕ `/plan-devex-review` — 개발자 경험(DX) 리뷰. API, CLI, SDK 제품용이라 해당 없음

#### ○ `/autoplan` — 리뷰 일괄 실행
- **목적**: CEO → 디자인 → DX → 엔지니어링 리뷰를 **자동 결정으로** 연속 실행하고, 판단이 갈리는 것만 마지막에 모아서 묻는다.
- **6가지 결정 원칙**: ① 완전성 ② 영향 범위 안은 다 고치기 ③ 실용성 ④ DRY ⑤ 명시적 > 영리함 ⑥ 행동 우선
- **결정 분류**
  - **Mechanical**: 정답이 하나 → 조용히 자동 결정
  - **Taste**: 의견이 갈릴 수 있음 → 추천과 함께 최종 게이트에 표시
  - **User Challenge**: 사용자가 정한 방향을 바꾸는 것 → **절대 자동 결정하지 않음**

#### ★ `/spec` — 실행 가능한 명세 → 이슈
- **목적**: 막연한 의도를 **구현 에이전트가 바로 실행할 수 있는 명세**로 만든다. GitHub 이슈로 등록하고, 원하면 새 worktree에서 Claude Code 에이전트를 띄운다. `/ship`이 머지할 때 이슈를 닫는다.
- **동작**: 5단계, 생략이나 병합 금지 — Why → 범위·경계 → 기술 심문(코드 먼저 읽기) → 초안 리뷰 → 품질 게이트 후 등록
- **이슈 품질 기준(14개)**: 왜 중요한지, 검증된 현재 상태, 스키마·API 형태, 파일 참조 표, **테스트 가능한 수용 기준**, 테스트 피라미드, **구현 에이전트를 위한 규칙**, 롤백 계획, 범위 밖, 의존성 그래프
- 👉 graffiti 기능(doodle 게시, 페이지 넘김 등)을 **기능 단위 이슈**로 쪼개 Claude Code에 넘길 때 이 템플릿을 쓰면 된다.

### 4.3 BUILD + REVIEW — 구현과 코드 리뷰

#### ★ `/review` — 머지 전 diff 리뷰 (`review/`)
- **목적**: CI는 통과하지만 **운영에서 터지는 버그**를 찾는다.
- **동작**: base 브랜치 대비 diff 전체 읽기 → `checklist.md`로 2패스 검사 → **Fix-First** (AUTO-FIX는 바로 고치고, ASK는 묶어서 질문) → 결과를 Review Log에 기록
- **Pass 1 CRITICAL 항목** (graffiti와 직결)
  - **SQL·데이터 안전**: 문자열로 이어 붙인 SQL, TOCTOU(확인 후 쓰기 경쟁), 검증 우회 직접 쓰기, N+1
  - **경쟁 조건**: 유니크 인덱스 없는 find-or-create, **원자적이지 않은 상태 전이**(`WHERE old_status=? UPDATE` 패턴을 쓰지 않음), XSS
  - **LLM 출력 신뢰 경계**: LLM이 만든 값을 형식 검증 없이 DB에 저장, 구조화 출력의 타입이나 형태를 검사하지 않음 ← **ChatKHU JSON 응답 파싱에 해당**
  - **Enum 완전성**: 새 상태값(예: `UNCHECKED`)을 추가했는데 모든 소비자(switch, 필터, 화면)가 처리하는지
  - **Gate Integrity**: 테스트 삭제, skip, 기준 완화 등
- **Fix-First 휴리스틱**
  - **AUTO-FIX**: 죽은 코드, N+1, 오래된 주석, 매직 넘버, LLM 출력 검증 누락
  - **ASK**: 보안, 경쟁 조건, 설계 결정, 20줄 넘는 수정, 기능 제거, 사용자에게 보이는 동작 변경, 게이트 무력화
- **Sideways sweep**: 버그 하나를 찾으면 **같은 패턴을 다른 진입점(라우트, 워커, 크론, 훅) 전부에서** 찾는다. 데이터 손실, 인증 우회, 무제한 비용(유료 API 호출 루프)은 발견 즉시 CRITICAL로 본다.
- **규칙**: diff 전체를 먼저 읽기, 짧게 쓰기, 진짜 문제만, **커밋·푸시·PR 금지**(그건 `/ship`의 일)

#### ★ `/investigate` — 체계적 디버깅
- **Iron Law**: **근본 원인을 찾기 전에는 수정하지 않는다.**
- **동작**: Phase 1 근본 원인 조사 → **Scope Lock**(영향받는 디렉토리로 freeze) → Phase 2 패턴 분석 → Phase 3 가설 검증(임시 로그나 assert로 증거 확인) → Phase 4 구현 → Phase 5 검증과 DEBUG REPORT
- **중요 동작**
  - **3-strike 규칙**: 가설이 3번 틀리면 멈추고 묻는다(계속 / 사람에게 넘김 / 로깅 추가 후 대기). 아키텍처 문제일 가능성이 있기 때문이다.
  - **회귀 테스트**: 수정 전에는 **실패**하고 수정 후에는 **통과**해야 의미가 있다.
  - 수정이 **5개 파일을 넘으면** 영향 범위를 확인받는다.
  - "이걸로 고쳐질 것 같아요" 금지. 재현하고 테스트를 실행해서 증명한다.
  - 결과를 learnings에 기록해서 다음 디버깅에 쓴다.

#### ○ `/codex`, `/claude-code` — 다른 모델의 2차 의견
- review(독립 리뷰, pass/fail) / challenge(깨뜨리기 시도) / consult(질문) 세 가지 모드
- **User Sovereignty**: 두 모델이 동의해도 사용자 방향을 바꾸려면 반드시 묻는다.

#### ○ `/deslop-shared-libs` — 공통 코드로 뽑을 만한 중복 찾기. 권고만 함
#### ○ `/test-audit` — 가치 낮은 테스트(구현에 묶인 테스트, 중복, 동어반복) 찾기. 보고만 함

### 4.4 TEST — QA와 보안

#### ★ `/qa` — 테스트하고 고치고 다시 검증 (`qa/`)
- **목적**: 브라우저, API, CLI, 워커, 웹훅 동작을 실제로 돌려 버그를 찾고 고친다.
- **동작**: 표면(surface) 선택 → 기준선 QA(Phase 1~6) → 분류 → **수정 루프** → 최종 QA → 리포트(`.gstack/qa-reports/`) → TODOS 업데이트
- **수정 루프(Phase 8)의 중요 동작**
  - 8a 재현 → **8a.5 수정 전에 회귀 테스트부터 작성해서 실패 확인**(`// Regression: ISSUE-NNN — Found by /qa on 날짜`) → 8b 최소 수정 → 8c 재테스트(회귀 테스트 + 원래 재현 + 인접 happy path)
  - 8d **검증된 수정만** 회귀 테스트와 함께 커밋: `fix(qa): ISSUE-NNN — ...`
  - 8e 분류: verified / best-effort / reverted
  - 8f **자기 규제**: 5개 수정마다 점검. revert가 2번 나오거나 관련 없는 파일을 수정하면 즉시 멈춤. 최대 50개
  - CI 설정 변경과 기존 테스트 약화는 금지
- `/qa-only`: 같은 QA지만 **보고만** 하고 코드는 건드리지 않는다.

#### ★ `/cso` — 보안 감사 (Chief Security Officer)
- **목적**: OWASP Top 10 + STRIDE 기반의 정적 보안 감사
- **동작**: 애플리케이션 모델 파악 → 공격 표면 조사 → 증거 기준과 독립 검증 → 리포트와 수정 후보
- 👉 graffiti의 `docs/SECURITY.md` 15개 항목을 기준으로 **배포 전 1회 이상** 돌린다.

#### ○ `/design-review` — 실제 화면을 보고 시각 QA하고 수정. 수정마다 before/after 스크린샷과 원자적 커밋
#### ○ `/benchmark` — 페이지 로드, Core Web Vitals 성능 회귀 감지

### 4.5 SHIP — 배포

#### ★ `/ship` — PR까지의 릴리스 파이프라인 (`ship/`)
- **목적**: "코드 다 됐다"부터 **PR 생성**까지를 정해진 게이트로 처리한다.
- **동작 (Step 순서)**
  1. Pre-flight
  2. 배포 파이프라인 확인
  3. **base 브랜치를 먼저 머지**(테스트 전에)
  4. 테스트 프레임워크가 없으면 부트스트랩
  5. **머지된 코드로 테스트 실행**
  6. (eval)
  7. **테스트 커버리지 감사**(빠진 테스트 생성)
  8. **계획 완료 감사**(계획한 걸 다 했는가)
  9. Pre-landing review(=`/review`)
  10. 리뷰 코멘트 처리
  11. 리뷰 바인딩
  12. VERSION bump(자동 결정, 기본은 PATCH)
  13. CHANGELOG
  14. TODOS 정리
  15. 문서 감사
  16. **커밋을 bisect 가능한 단위로**
  17. 최종 검증 게이트
  18. push
  19. PR 생성
- **중요 동작**
  - **force push 금지**
  - 실패한 테스트는 커밋하지 않는다.
  - 중간에 수정이 생기면 해당 단계부터 다시 밟는다(작업 목록 재삽입 규칙).
  - 자식 에이전트의 "통과했다"는 증거일 뿐, **진행 허가가 아니다.** 부모가 확인한다.

#### ○ `/land-and-deploy` — PR 머지 → CI 대기 → 배포 대기 → canary 검증 → 문제 시 revert → 배포 리포트
- 배포 방식별 전략: A) GitHub Actions B) 플랫폼 CLI C) 자동 배포 플랫폼 D) 커스텀 훅. graffiti는 **A(GitHub Actions → EC2)**에 해당
- 처음 실행할 때는 dry-run으로 설정을 검증한다.

#### ○ `/setup-deploy` — 배포 플랫폼, 운영 URL, 헬스체크 엔드포인트를 감지해서 **CLAUDE.md에 기록**한다. 이후 배포가 자동화된다.
#### ○ `/canary` — 배포 후 콘솔 에러, 성능, 페이지 실패를 감시한다. 배포 전 기준선과 비교
#### ★ `/document-release` — 배포할 때마다 diff와 문서(README, ARCHITECTURE, CLAUDE.md)를 대조해서 어긋난 부분을 고치고, 다이어그램 drift를 찾는다.
#### ○ `/document-generate` — Diataxis(튜토리얼, how-to, 레퍼런스, 설명) 형식으로 문서를 새로 생성

### 4.6 REFLECT / 운영 / 메모리

| 스킬 | 적용 | 목적과 핵심 동작 |
|---|---|---|
| `/context-save` | ★ | git 상태, 결정, 남은 작업을 저장한다. 팀원끼리 세션을 넘길 때나 다음 날 이어서 할 때 |
| `/context-restore` | ★ | 저장된 맥락을 불러와 이어간다(현재 브랜치 우선) |
| `/learn` | ○ | 세션 간에 쌓인 교훈을 조회, 검색, 정리 |
| `/retro` | ○ | 주간 회고. 커밋 이력 분석, **팀원별 기여**와 칭찬, 성장 포인트 → 팀 프로젝트에 유용 |
| `/health` | ○ | 타입 체커, 린터, 테스트, 죽은 코드를 묶어 0~10점 품질 점수와 추세를 계산 |
| `/plan-tune` | ✕ | 질문 민감도 개인화 |
| `/benchmark-models`, `/eval-plan` | ✕ | 모델 비교, 유료 eval 설계 (gstack 자체 개발용) |
| `/setup-gbrain`, `/sync-gbrain` | ✕ | 머신 간 메모리 동기화 (선택 인프라) |
| `/gstack-upgrade` | — | gstack 업데이트 |

### 4.7 안전과 범위 제한

| 스킬 | 적용 | 동작 |
|---|---|---|
| `/careful` | ★ | PreToolUse 훅으로 `rm -rf`, `DROP TABLE`, force-push, `reset --hard` 전에 경고 → **운영 EC2나 DB를 만질 때 필수** |
| `/freeze <dir>` | ★ | 지정 디렉토리 밖의 Edit/Write를 **차단**. 예: 백엔드 작업 중 `frontend/` 수정 방지 |
| `/guard` | ○ | careful + freeze 동시 적용 |
| `/unfreeze` | — | freeze 해제 |

### 4.8 무관한 영역 (참고만)

- **브라우저**: `/browse`, `/open-gstack-browser`, `/setup-browser-cookies`, `/pair-agent`, `/scrape`, `/skillify` — Aside 브라우저나 자체 Chromium 구동. `/qa`가 내부적으로 쓴다.
- **iOS**: `/ios-qa`, `/ios-fix`, `/ios-design-review`, `/ios-clean`, `/ios-sync`
- **디자인 생성**: `/design-consultation`(DESIGN.md 작성 ○), `/design-shotgun`, `/design-html`
- **기타**: `/make-pdf`, `/diagram`, `/landing-report`, `/devex-review`, `openclaw/`, `hosts/`(Codex, Cursor 등 호스트별 설정)

---

## 5. 모든 역할에 공통으로 흐르는 규칙

이 부분이 graffiti CLAUDE.md로 가져갈 핵심이다.

### 5.1 ETHOS (판단 기준)
1. **Boil the Ocean**: AI에게 완전성은 싸다. "90%만 하는 80줄" 대신 "완전한 150줄"을 선택한다. 테스트를 "다음 PR로 미루기"는 금지
2. **Search Before Building**: 동시성, 인프라, 낯선 패턴은 **"{런타임} {기능} built-in"을 먼저 검색**한다. Layer 1(검증된 방식) / 2(최신 유행, 비판적으로) / 3(제1원리, 가장 귀함)
3. **User Sovereignty**: AI는 추천하고 사용자가 결정한다. 두 모델이 동의해도 사용자 방향을 바꾸려면 묻는다. "바꾸고 나중에 말하기" 금지
4. **Honest Work**: 테스트 삭제, skip, 타임아웃 늘리기, `--no-verify`는 사람이 이유와 함께 직접 결정한다. **보고는 다시 실행해서 확인하기 전까지 주장일 뿐**이다. "완료"와 "완료(미검증)"는 다른 보고다.

### 5.2 상호작용 규칙
- **질문은 한 번에 하나**, 항상 `RECOMMENDATION: X를 고르세요, 이유는 ___` 형식으로 추천을 포함
- 결정에는 번호(`D1`, `D2`…)를 붙이고 원장에 남긴다.
- **내구성 있는 결정**(아키텍처, 범위 축소, 도구 선택, 결정 번복)은 이유와 함께 기록한다. 기록된 결정은 "확정"으로 보고, 뒤집을 때는 명시적으로 말한다.
- 완료 상태는 4가지로 통일: `DONE` / `DONE_WITH_CONCERNS` / `BLOCKED` / `NEEDS_CONTEXT`

### 5.3 코드와 커밋 규칙
- **Bisect 커밋**: 커밋 하나에 논리적 변경 하나. 이름 변경과 동작 변경은 분리, 기계적 리팩터링과 기능은 분리
- `git add .` / `git add -A` 금지. **파일을 지정해서** stage
- force push 금지, 실패한 테스트 커밋 금지
- 수정 전 회귀 테스트(red → green). "회귀를 못 잡는 테스트"는 만들지 않음
- 다이어그램은 코드와 **같은 커밋에서** 갱신
- CLAUDE.md는 짧게 유지하고 세부는 `docs/`로

### 5.4 Gate Integrity (게이트 무력화 패턴 = 리뷰에서 반드시 ASK)

| ID | 패턴 |
|---|---|
| RH-1 | 테스트 삭제, `@Disabled`, `.skip` |
| RH-2 | mock이나 fixture를 실제 검증인 것처럼 제시 |
| RH-3 | 스냅샷을 소유 테스트 수정 없이 재생성 |
| RH-4 | placeholder(TODO 구현) 커밋 |
| RH-5 | 실패할 수 없는 테스트 (자기 setup만 검사, 구현을 그대로 베낌) |
| RH-10 | 명세를 고쳐서 "진척"으로 포장 |
| RH-12 | 제품 코드에서 테스트 환경 감지(`if (isTest)`) |
| RH-13 | 경고 억제 pragma(`@SuppressWarnings` 등) 추가 |
| RH-14 | 게이트 우회 (`--no-verify`, CI skip) |
| RH-15 | 기준 완화 (타임아웃이나 임계값 늘리기) |
| RH-16 | 예외 삼키기 (빈 catch) |

### 5.5 Validation discipline (AGENTS.md 요약)
1. 실패 목록을 하나만 유지한다(로그, 원인, 증명할 최소 확인).
2. 공유 파일은 담당자를 한 명씩 정하고 범위 안에서만 수정한다.
3. **고치기 전에 진단한다**: 제품 결함 / 잘못된 테스트 기대값 / fixture 결함 / 환경 문제를 구분한다. 증거 없이 "원래 있던 문제"라고 하지 않는다(main에서도 실패하는지 보여줘야 함).
4. 가장 작은 테스트로 재현한다. 기준을 낮추거나 케이스를 빼서 통과시키지 않는다.
5. 싼 검사(린트, 시크릿 스캔)부터 돌린다.
6. ~7. 집중 테스트로 수정을 증명한다.
8. **코드를 동결한 뒤 전체 테스트를 마지막에 1번** 돌린다.
9. 필수 검사가 통과해야만 배포한다. 리비전, 통과/실패/스킵 수, 빠진 커버리지를 보고한다. "일부 통과"는 릴리스 승인이 아니다.

---

## 6. gstack CLAUDE.md는 어떻게 생겼나 (작성 패턴)

| 섹션 | 내용 | graffiti에 대응 |
|---|---|---|
| Commands | 설치, 개발, 타입체크, 빌드, 테스트 명령 | `./gradlew test`, `npm test`, `docker compose up` |
| Testing | 어떤 테스트를 언제, Validation discipline 링크, **테스트 가치 기준** | 필수 단위 테스트 목록, 보안 거부 테스트 |
| Project structure | 한 단락짜리 지도 + 상세 문서 링크 | `backend/`, `frontend/`, `docs/` |
| (도메인 특화 규칙) | 템플릿 작성법, 바이너리 커밋 금지, 시크릿 가드 | **도메인 불변 조건 10개, 보안 규칙** |
| Commit style | bisect 커밋 | 동일하게 + Conventional Commits |
| Search before building | 동시성이나 인프라는 검색 먼저 | Spring/STOMP built-in 먼저 확인 |
| Skill routing | 요청 유형 → 스킬 매핑 | gstack을 설치하면 그대로 사용 |
| Cross-session decision memory | 결정 기록과 재조회 | `docs/DECISIONS.md` (팀 공유용으로 저장소에) |
| **프로젝트 설정 키** | 스킬들이 CLAUDE.md에서 읽는 값: `## Test Coverage`(Minimum/Target), 배포 설정(`/setup-deploy`가 기록) | 테스트 명령, 커버리지 목표, 배포 URL, 헬스체크 |

> **Platform-agnostic 원칙**: gstack 스킬은 프레임워크별 명령을 하드코딩하지 않는다. **프로젝트 CLAUDE.md를 읽고, 없으면 사용자에게 묻고, 답을 CLAUDE.md에 저장**한다. 그래서 Spring + React 프로젝트에도 그대로 쓸 수 있다. 우리 CLAUDE.md에 명령어와 설정을 정확히 적어두는 게 중요한 이유다.

---

## 7. graffiti 적용 결과

### 7.1 적용 방식: B (핵심 증류) — D-26

처음에는 "무거운 워크플로는 gstack을 설치해서 쓰고 프로젝트 규칙만 CLAUDE.md에 쓰는" A+B 혼합을 추천했다. 하지만 실제 크기를 재 보니 판단이 바뀌었다.

| 스킬 | SKILL.md 본문 | sections 포함 |
|---|---|---|
| `/office-hours` | 1,347줄, 약 2.4만 토큰 | +약 1.5만 토큰 |
| `/review` | 1,205줄, 약 2만 토큰 | +약 1.5만 토큰 |
| `/ship` | 1,224줄, 약 2만 토큰 | +약 5.7만 토큰 |
| `/qa`, `/investigate`, `/plan-eng-review` | 약 1.3만 토큰씩 | +0.3~2.5만 |

- 호출할 때마다 수만 토큰이 컨텍스트를 차지해서 **우리 도메인 규칙이 묻힌다.**
- Greptile, Codex, Aside 브라우저, gbrain, Bun 헬퍼처럼 **우리 환경에 없는 도구를 전제한 단계**가 많다.
- 팀원 전원이 Bun과 `./setup`을 설치해야 한다.

그래서 gstack은 설치하지 않고, **규칙과 절차의 핵심만** 이 저장소에 옮겼다(스킬 하나당 100~200줄).

### 7.2 어디에 무엇이 들어갔나

| gstack 원본 | graffiti | 위치 |
|---|---|---|
| ETHOS, 공통 규칙 (§5) | 작업 규칙 14개 | `CLAUDE.md` §6 |
| `/spec` + `/plan-eng-review` | `/gf-spec` | `.claude/skills/gf-spec/` |
| `/review` + `checklist.md` + gate-integrity | `/gf-review` + 프로젝트용 체크리스트 | `.claude/skills/gf-review/` |
| `/qa` | `/gf-qa` (STOMP 다중 클라이언트 중심) | `.claude/skills/gf-qa/` |
| `/investigate` | `/gf-investigate` | `.claude/skills/gf-investigate/` |
| `/cso` | `/gf-security` (SEC-01~17) | `.claude/skills/gf-security/` |
| `/ship` | `/gf-ship` | `.claude/skills/gf-ship/` |
| `/land-and-deploy` + `/canary` | `/gf-deploy-check` | `.claude/skills/gf-deploy-check/` |
| `/context-save` + `/context-restore` | `/gf-handoff` | `.claude/skills/gf-handoff/`, `.handoff/` |
| `/careful` 훅 | 항상 켜진 PreToolUse 훅 | `.claude/hooks/check-careful.sh`, `.claude/settings.json` |
| decisions.jsonl | 결정 원장 | `docs/DECISIONS.md` |
| Review Log | 명세 안의 리뷰·QA 기록 (커밋 SHA 포함) | `docs/specs/NN-*.md` |
| TODOS.md | 동일 | `TODOS.md` |

### 7.3 파이프라인

`docs/PIPELINE.md`에 정리했다. 원래 제안했던 흐름에서 바꾼 점:
- `/plan-eng-review`는 `/gf-spec`에 흡수했다. 아키텍처는 설계 인터뷰로 이미 잠겼기 때문이다.
- `/freeze`는 수직 슬라이스 구현과 충돌해서 빼고, `/gf-investigate`의 범위 고정 규칙으로만 남겼다.
- `/cso`는 "보안 기능일 때만"이 아니라 **배포 전 게이트**로 옮기고, 매 리뷰에서 SEC 체크리스트를 확인하게 했다.
- `/land-and-deploy`와 `/retro`는 MVP 하네스에서 뺐다. 배포는 GitHub Actions가 하고, 확인만 `/gf-deploy-check`로 한다.
