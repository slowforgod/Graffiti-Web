# CLAUDE.md — graffiti (경희대 온라인 낙서장)

모든 사용자가 하나의 거대한 캔버스에 펜과 텍스트로 낙서하고, 점유율 70%가 되면 6시간 뒤 새 페이지로 넘어가는 실시간 웹 서비스.
이 파일은 **항상 지킬 규칙**만 담는다. 상세는 `docs/`에 있고, 규칙 원문은 ID(D-xx, INV-xx, SEC-xx)로 찾는다.

## 1. 문서 지도 — 작업 전에 관련 문서를 먼저 읽는다

| 하려는 일 | 읽을 문서 |
|---|---|
| 왜 이렇게 정했는지 | `docs/DECISIONS.md` (D-xx) |
| 구조, 흐름, 불변 조건 | `docs/ARCHITECTURE.md` (§5 INV-xx, §6 설정값) |
| 테이블, 쿼리 | `docs/DB.md` |
| REST, STOMP, 에러 코드 | `docs/API.md` |
| ChatKHU(LLM 게이트웨이) 호출 | `docs/CHATKHU_API.md` |
| 텍스트 판정 기준(욕설, 혐오, 성적, 개인정보) | `docs/FILTER.md` |
| 보안 규칙 | `docs/SECURITY.md` (SEC-xx) |
| 개발 절차와 게이트, 로드맵 | `docs/PIPELINE.md` |
| 지금 구현할 기능 | `docs/specs/NN-*.md` (상태가 APPROVED인 것만 구현) |
| 자체 ML 모더레이션 | `docs/DECISIONS.md` D-34~D-40, `ARCHITECTURE.md` §4.7, `API.md` §7 |
| 미룬 것 | `TODOS.md` |

**문서가 기준이다.** 코드와 문서가 다르면 문서가 맞다. 문서에 없거나 모호하면 추측하지 말고 멈춰서 질문한다(`NEEDS_CONTEXT`).

## 2. 명령어와 설정

> backend, frontend 명령은 `00-bootstrap`, `ml/` 명령은 `ml-prep`에서 확정했다. ML 서버는 Python 3.12.
> 요구사항: Java 21, Node 22, Docker(로컬 MySQL과 Testcontainers). 비밀값은 루트의 `.env`에 사람이 채운다(SEC-06, 키 목록은 `README.md`).

```bash
docker compose up -d mysql                 # 로컬 DB (.env의 DB_USERNAME/DB_PASSWORD/DB_ROOT_PASSWORD, 127.0.0.1:3306)
cd backend && ./gradlew bootRun            # 백엔드 (localhost:8080, DB_URL/DB_USERNAME/DB_PASSWORD 환경변수 필요)
cd backend && ./gradlew test               # 백엔드 전체 테스트 (Testcontainers 사용 → Docker 필요)
cd backend && ./gradlew jacocoTestReport   # 커버리지 (test 후 자동 생성, build/reports/jacoco)
cd frontend && npm ci && npm run dev       # 프론트 (localhost:5173, /api와 /ws는 8080으로 프록시)
cd frontend && npm test                    # 프론트 테스트 (Vitest)
cd frontend && npm run lint                # ESLint (dangerouslySetInnerHTML 금지 규칙 포함)
cd frontend && npm run build               # 프로덕션 빌드
cd ml/serving && pip install -r requirements-dev.txt && uvicorn app.main:build --factory --port 8000   # ML 서버 (MODEL_DIR의 가중치를 models.lock 체크섬으로 검증, 모델이 없으면 503)
cd ml/serving && pytest                     # ML 서버 테스트
cd ml/eval && pytest && python evaluate.py --manifest <세트>/manifest.csv   # 평가 세트로 D-39 지표 계산 (ml/README.md)
cd ml/eval && python build_set.py --out sets/<세트> --quickdraw <ndjson...> --hate-variants 300 --team <폴더>   # 평가 세트 생성
cd ml/eval && python calibrate.py --manifest sets/<세트>/manifest.csv   # 카테고리별 임계값 보정 + 보고 세트 D-39 지표
cd ml/eval && python ocr_eval.py --model-dir <가중치> --manifest <손글씨 세트>/manifest.csv   # OCR 글자 오류율 + min_confidence
```

**전체 검사** (PR 전, `/gf-ship`이 실행): `./gradlew test` + `npm test` + `npm run lint` + `npm run build` + `pytest`(ml/serving, ml/eval)

CI(`.github/workflows/ci.yml`)는 PR과 main push마다 backend `./gradlew test`, frontend `npm ci` → lint → test → build, ml `pytest`(serving, eval), gitleaks 시크릿 스캔을 실행한다. main에서 CI가 통과하면 `deploy.yml`이 이어서 돈다.

### Test Coverage
- Minimum: 도메인 패키지(`page`, `doodle`, `moderation`의 순수 로직) 라인 80% (D-32, PROPOSED)
- 그 외 패키지: 목표값 없음. 대신 명세의 수용 기준과 SEC 거부 테스트가 전부 있어야 함

### 배포 설정 (`/gf-deploy-check`가 읽음)
- 운영 URL: (12b에서 기입) / 스테이징 URL: 저장소 변수 `STAGING_HOST` (서버 준비 후 기입, `deploy/README.md`)
- 헬스체크: `/actuator/health`
- 배포 방식: main 머지 → CI 통과 → `.github/workflows/deploy.yml`이 GHCR에 `graffiti-app`, `graffiti-web`, `graffiti-ml` 이미지(태그 = 커밋 SHA) push → `STAGING_HOST`가 있으면 EC2 `/opt/graffiti`에서 `docker compose pull && up -d`
- 배포 구성 파일: `deploy/` (Dockerfile 2개, `compose.yml`, nginx 설정, 준비 절차 `README.md`)
- 환경변수 목록: `README.md`의 `.env` 표 (값은 GitHub Secrets와 EC2의 `/opt/graffiti/.env`에만)

## 3. 범위
- **MVP에 없는 것을 만들지 않는다**: 아카이브 열람, 신고, 자동 차단, 잉크 시스템, 누적 접속자, 작성자 표시, 텍스트 수정, 게시된 doodle 부분 지우기. 있으면 좋을 것 같은 기능도 코드에 넣지 말고 `TODOS.md`에 추가한다.
- 구현은 **APPROVED 상태 명세의 범위만** 한다.

## 4. 도메인 불변 조건 (원문: `docs/ARCHITECTURE.md` §5)
- **INV-01** 페이지 상태 변경(점유율, 페이지 상태, 쿨다운, doodle 게시·삭제·숨김·복구)은 전부 `PageCommandProcessor`를 거친다
- **INV-02** 큐 안에서 AI 호출, 렌더링, 외부 HTTP, 대량 조회를 하지 않는다
- **INV-03** `ACTIVE → COUNTDOWN → ENDED` 한 방향, 카운트다운 취소 없음, 종료 = `min(생성+7일, 70%도달+6시간)`, 전이는 이전 상태를 조건으로 UPDATE
- **INV-04** 모든 쓰기는 `pageId`를 포함하고 현재 페이지가 아니면 `PAGE_ENDED`
- **INV-05** 모더레이션 결과가 PASSED/UNCHECKED일 때만 게시
- **INV-06** 초안은 저장하지 않는다
- **INV-07** doodle과 page 행은 삭제하지 않는다(상태만 변경)
- **INV-08** 쿨다운은 큐 안에서 확인. 거부돼도 소모, 삭제해도 반환 없음
- **INV-09** 클라이언트 로딩은 구독 → REST 조회 → ID 병합
- **INV-10** 수치는 `GraffitiProperties` 한 곳에만. 숫자 하드코딩 금지
- **INV-11** 종료되지 않은 페이지는 최대 1개
- **INV-12** 점유율 = 선분 bbox 칸 참조 카운트. DB에서 언제든 재계산 가능해야 함
- **INV-13** ML 모드가 SHADOW면 ML 판정은 게시 결과에 영향 없음(기록만). ML 장애가 게시를 막지 않음

## 5. 보안 규칙 (원문: `docs/SECURITY.md`)
- **SEC-01** 파라미터 바인딩만. SQL 문자열 조립 금지
- **SEC-02** 서버가 모든 입력을 재검증. 작성자와 권한은 **세션에서만**, 요청 본문의 userId 무시
- **SEC-03** CSRF 토큰, 쿠키 `HttpOnly`·`Secure`·`SameSite=Lax`, WebSocket `Origin` 허용 목록
- **SEC-04** 도메인 검증은 `hd`, `email_verified`, `@khu.ac.kr` 접미사 **셋 다**
- **SEC-05** LLM 입력은 구분자로 감싸고, 응답은 스키마로 엄격히 파싱. 텍스트는 이중 검사
- **SEC-06** 비밀값은 환경변수로만. `.env`는 읽지도, 쓰지도, 커밋하지도 않는다
- **SEC-07** REST와 STOMP **양쪽 모두** 인증, 인가, 검증 (`ChannelInterceptor`)
- **SEC-08/09** 새 엔드포인트와 메시지 타입에는 속도 제한과 크기 상한
- **SEC-10/11** DB 포트 비공개, actuator는 health만, 백업
- **SEC-12** `dangerouslySetInnerHTML` 금지, 사용자 텍스트는 캔버스로만
- **SEC-13** 로그에 이메일, 토큰, 세션 ID 금지. 공개 응답에 작성자 정보 금지
- **SEC-14** 관리자 조치는 전부 `moderation_action`에 기록
- **SEC-15** 차단 여부는 ✓와 STOMP SEND마다 확인. 외부 API에는 타임아웃
- **SEC-16** 의존성 버전 고정 (Gradle, npm, pip)
- **SEC-17** ML 서버는 내부망 전용, OCR 글자와 이미지는 로그 금지, 학습 추출 데이터에 작성자 정보 금지

## 6. 작업 규칙 (gstack에서 가져옴, `docs/harness/GSTACK_HARNESS.md`)

1. **문서가 기준이다.** 모호하면 추측하지 말고 `NEEDS_CONTEXT`로 멈춘다.
2. **질문은 한 번에 하나**, 항상 추천을 붙인다: `추천: A — 이유`.
3. **범위 밖 기능 금지.** 미룬 것은 `TODOS.md`에 What/Why/Context로 기록한다.
4. **Search Before Building.** 동시성, 스케줄링, 속도 제한, 세션, WebSocket 같은 인프라는 Spring/React 내장 기능과 공식 문서를 먼저 확인한다. 직접 만들기 전에 "이미 있는가?"를 묻는다.
5. **근본 원인 없이 수정하지 않는다.** 버그는 `/gf-investigate`. 가설 3개가 실패하면 멈추고 묻는다.
6. **테스트 먼저.** 기능은 TDD로, 버그는 회귀 테스트로 실패를 확인한 뒤(red) 수정한다(green). 회귀를 잡지 못하는 테스트(자기 setup만 검사, 구현 복사, 단언 없음)는 만들지 않는다.
7. **Gate Integrity.** 테스트 삭제, `@Disabled`, `.skip`, 타임아웃이나 임계값 완화, 빈 catch, `@SuppressWarnings`, `eslint-disable`, `--no-verify`, CI 단계 skip은 **사람이 이유와 함께 승인해야** 한다. 스스로 하지 않는다.
8. **AUTO-FIX vs ASK.** 시니어 개발자가 논의 없이 고칠 기계적 수정(죽은 코드, 매직 넘버, N+1, 낡은 주석)은 바로 고친다. 보안, 경쟁 조건, 설계 결정, 20줄 넘는 수정, 기능 제거, 사용자에게 보이는 동작 변경은 묻는다. (기준: `.claude/skills/gf-review/checklist.md`)
9. **Honest Work.** "완료"라고 하기 전에 테스트를 **실제로 실행하고** 결과(통과/실패/스킵 수)를 첨부한다. 실행하지 않았으면 "완료(미검증)"라고 구분해서 쓴다. mock 결과를 실제 동작 증명으로 제시하지 않는다. 근거 없이 "원래 있던 문제"라고 하지 않는다.
10. **커밋 규칙.** 커밋 하나에 논리적 변경 하나(bisect 가능). 파일을 지정해서 stage하고 `git add -A`/`git add .`는 쓰지 않는다. force push 금지, main 직접 push 금지. 메시지는 Conventional Commits(`feat(publish): ...`). 브랜치 이름은 `<type>/[NN-]<짧은-설명>`(`docs/PIPELINE.md` §5).
11. **문서와 다이어그램은 코드와 같은 커밋에서** 갱신한다: API → `API.md`, 스키마 → `DB.md`, 흐름·컴포넌트 → `ARCHITECTURE.md`.
12. **완료 상태는 4가지로 보고한다**: `DONE` / `DONE_WITH_CONCERNS`(우려 목록 포함) / `BLOCKED`(무엇이 막혔고 무엇이 필요한지) / `NEEDS_CONTEXT`(무엇을 모르는지).
13. **결정은 스스로 바꾸지 않는다.** 결정과 다르게 가야 하면 멈추고 보고한다. 팀이 합의하면 `docs/DECISIONS.md`에 새 항목을 추가하고 기존 항목은 `SUPERSEDED`로 표시한다.
14. **명령어, 커버리지 목표, 배포 설정은 이 파일 §2에** 둔다. 바뀌면 여기를 갱신한다.

## 7. 코드 규칙
- 백엔드는 기능별 패키지(`auth`, `user`, `page`, `doodle`, `moderation`, `realtime`, `admin`, `common`, `config`). 순수 로직은 Spring 의존 없는 클래스로 만들고 단위 테스트한다.
- 현재 시각은 주입받은 `Clock`으로만 얻는다. `LocalDateTime.now()` 직접 호출 금지. 저장은 UTC.
- 외부 API 호출에는 타임아웃을 필수로 둔다(ML 서버 호출 포함, 3초).
- `ml/`은 Python. 모델 가중치, 학습 이미지, 데이터 추출본은 **git에 넣지 않는다**(S3 + `ml/models.lock`). ML 요청·응답 스키마를 바꾸면 `ml/serving`, `MlModerationProvider`, `API.md` §7을 같은 PR에서 고친다.
- ML 모드(`OFF`/`SHADOW`/`ENFORCE`)를 코드에서 바꾸지 않는다. `ENFORCE` 전환은 D-39 기준 + 팀 승인 후 설정값으로만.
- 코드, 식별자, 주석은 영어. **사용자에게 보이는 문구는 한국어.**
- 요청받지 않은 리팩터링은 하지 않는다. 기존 코드 스타일을 따른다.

## 8. 스킬 라우팅

| 상황 | 사용 |
|---|---|
| 다음 기능을 시작한다, 명세가 필요하다 | `/gf-spec NN` |
| APPROVED 명세를 구현한다 | `/gf-implement NN` |
| 구현부터 QA까지 자동으로 돌린다 | `/gf-run NN` (사용자가 직접 호출, 결정이 필요하면 멈춤, 끝나면 `.gf-run/reports/`에 요약 보고서) |
| 구현을 마쳤고 머지 전 점검이 필요하다 | `/gf-review` |
| 동작을 확인하고 버그를 찾는다 | `/gf-qa NN` (보고만: `report-only`) |
| 에러, 테스트 실패, 이상 동작 | `/gf-investigate` (직접 고치지 말 것) |
| 배포 전 보안 점검 | `/gf-security` |
| PR을 만든다 | `/gf-ship` (사용자가 직접 호출) |
| 배포 후 확인 | `/gf-deploy-check` (사용자가 직접 호출) |
| 작업 저장, 이어받기 | `/gf-handoff save` / `load` |

각 스킬의 모델과 effort는 `SKILL.md` frontmatter에 고정돼 있다(`docs/PIPELINE.md` §7). `gf-run-*`는 `/gf-run` 전용 내부 단계이므로 직접 쓰지 않는다.

위험한 셸 명령(`rm -r`, `DROP`, `TRUNCATE`, force push, `reset --hard`, `git add -A`, `--no-verify`, 볼륨 삭제)은 `.claude/hooks/check-careful.sh`가 실행 전에 확인을 요청한다.
