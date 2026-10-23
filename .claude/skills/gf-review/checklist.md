# graffiti 리뷰 체크리스트

> gstack `review/checklist.md`를 이 프로젝트(Spring + STOMP + MySQL + Konva)에 맞게 줄인 것.
> 불변 조건과 보안 규칙의 원문은 `docs/ARCHITECTURE.md` §5와 `docs/SECURITY.md`에 있다. 여기서는 **diff에서 무엇을 찾을지**만 적는다.

## Pass 1 — CRITICAL

### A. 불변 조건 (INV)
- **INV-01/02 큐 우회·큐 안 지연**: `PageState`, `OccupancyGrid`, `page.status`, `users.last_posted_at`, doodle 상태를 `PageCommandProcessor` 밖에서 바꾸는 코드. 큐 작업 안에서 AI 호출, `DoodleRenderer`, `RestClient`/`WebClient`, 대량 SELECT, `Thread.sleep`
- **INV-03 상태 전이**: `status`를 이전 상태 조건 없이 UPDATE. COUNTDOWN → ACTIVE 같은 역방향. `countdown_started_at` 재기록. 종료 시각 계산식 변경
- **INV-04 pageId**: 쓰기 경로(REST, STOMP SEND)에서 현재 페이지 확인 누락
- **INV-05 모더레이션 우회**: `PublishDoodle`에 모더레이션 결과 없이 도달하는 경로, 관리자 복구 외에 `REJECTED → PUBLISHED`로 가는 경로
- **INV-06 초안 저장**: 초안 메시지를 DB, 파일, 캐시(Map 보관 포함)에 저장
- **INV-07 물리 삭제**: `DELETE FROM doodle/page`, JPA `delete()`/`deleteById()`
- **INV-08 쿨다운**: 큐 밖에서 쿨다운 판정, AI 거부 시 쿨다운 미소모, 삭제 시 반환, 관리자 면제 누락
- **INV-09 로딩 순서**: 프론트에서 REST 조회 후 구독, 병합 시 ID 중복 제거나 `doodle:removed` 반영 누락
- **INV-10 매직 넘버**: 7680, 4320, 480, 48, 1024, 0.7, 1800, 2097152 같은 값이 `GraffitiProperties` 밖에 하드코딩 (테스트 fixture는 예외)
- **INV-11/12**: `live_flag` 제약 제거, 점유율 계산이 선분 bbox 방식과 다름, 재시작 시 재계산 경로 누락
- **INV-13 그림자 모드**: `mode=SHADOW`인데 ML 판정이나 OCR 텍스트 검사 결과가 최종 판정에 반영됨, ML 서버 장애·지연이 게시 실패나 `UNCHECKED`로 이어짐, `ml_prediction` 기록 실패가 게시 트랜잭션을 롤백시킴, ML 호출이 OpenAI 호출과 직렬로 묶여 제한 시간을 넘김

### B. 보안 (SEC)
- **SEC-01**: 문자열로 SQL 조립(`"... IN (" + ids + ")"`), `@Query(nativeQuery)`에 문자열 연결
- **SEC-02**: 요청 본문의 `userId`/`authorId` 사용, 서버 재검증 누락(범위, 팔레트, 굵기, 크기, 글자 수, 2MB), 클라이언트가 보낸 그림 bbox를 그대로 사용, 알 수 없는 필드 허용
- **SEC-03**: CSRF 비활성화(`csrf().disable()`), `allowedOrigins("*")`, 쿠키 속성 누락
- **SEC-04**: 도메인 검증 세 조건 중 일부만 검사, `endsWith("khu.ac.kr")`처럼 `@` 없는 비교
- **SEC-05 LLM 출력 신뢰**: ChatKHU/OpenAI 응답을 타입·스키마 검사 없이 사용, 사용자 텍스트를 구분자 없이 프롬프트에 삽입, 응답 텍스트를 길이 제한 없이 DB 저장
- **SEC-07**: `/api/admin/**` 권한 누락, 남의 doodle 삭제 가능, STOMP `ChannelInterceptor`에서 SEND/SUBSCRIBE 검사 누락
- **SEC-08/09**: 새 엔드포인트나 메시지 타입에 속도 제한 누락, 목록 크기 상한 없음
- **SEC-13**: 로그에 이메일, 토큰, 세션 ID 출력. 공개 응답에 작성자 정보 포함
- **SEC-15**: 차단 확인이 로그인 때만 있음, 외부 API 호출에 타임아웃 없음
- **SEC-17**: `docker-compose.yml`에서 ml 서비스에 `ports:` 공개, nginx가 ML 경로를 프록시, Python에서 OCR 글자나 이미지 바이트를 `logging`/`print`, 학습 추출 쿼리나 스크립트에 `author_id`·닉네임·이메일 포함, 모델 가중치 파일을 git에 추가, `models.lock` 체크섬 검증 없이 로드

### C. 경쟁 조건
- 확인 후 쓰기(check-then-act)를 큐나 DB 제약 없이 수행 (예: 닉네임 중복 확인 후 INSERT → 유니크 제약 + 재시도 필요)
- 유니크 인덱스 없는 find-or-create (사용자 첫 로그인)
- 큐 작업에서 DB 커밋 전에 메모리 상태를 바꾸거나 브로드캐스트함 (DB 실패 시 불일치)
- 프론트: 같은 요청 중복 전송(✓ 더블클릭), 응답 순서 역전, 페이지 넘김 중 이전 페이지 이벤트 처리

### D. Enum·값 완전성
- 새 상태값(doodle status, moderation_state, 에러 코드, 메시지 type)을 추가하면 **모든 소비자**를 읽는다: switch/if 체인, 쿼리 WHERE 조건, 프론트 타입과 분기, 관리자 화면 필터, `API.md`

### E. Gate Integrity (항상 ASK)
| 태그 | 찾을 것 |
|---|---|
| RH-1 | 테스트 삭제, `@Disabled`, `assumeTrue(false)`, `it.skip`, `.only` |
| RH-2 | mock이나 fixture를 실제 동작 증명으로 제시 (예: 모더레이션을 mock하고 "검사 통과 확인" 주장) |
| RH-4 | `TODO` 구현, `throw new UnsupportedOperationException()` 커밋 |
| RH-5 | 실패할 수 없는 테스트 (자기 setup만 검사, 구현 복사, 단언 없음) |
| RH-12 | 제품 코드의 테스트 환경 감지 (`if (isTest)`, 프로필로 검증 끄기) |
| RH-13 | `@SuppressWarnings`, `eslint-disable`, `@ts-ignore` 추가 |
| RH-14 | CI 단계 skip, `-x test`, `--no-verify` |
| RH-15 | 타임아웃, 임계값, 커버리지 기준 완화 |
| RH-16 | 빈 catch, 예외를 로그만 찍고 삼킴 (모더레이션 장애 → UNCHECKED처럼 **명세된 처리**는 예외) |

판단 질문: **"이 완화는 원래 잡으려던 실패를 숨기는가, 아니면 diff에 보이는 측정값이나 결정으로 정당화되는가?"**

## Pass 2 — INFORMATIONAL
- 테스트 빈틈: 명세의 수용 기준이나 실패 모드 중 테스트가 없는 것, SEC 거부 테스트 누락
- 에러 처리: 조용한 실패, 사용자에게 보이는 결과 없음, 에러 코드가 `API.md` 표와 다름
- N+1 쿼리, 인덱스 없는 조회, 상한 없는 목록
- 죽은 코드, 사용하지 않는 변수, 낡은 주석
- 문서 동기화: API/DB/흐름을 바꿨는데 `API.md`/`DB.md`/`ARCHITECTURE.md` 다이어그램이 같은 변경에 없음
- 범위 밖 기능 추가 (명세의 "제외" 항목이나 `TODOS.md` 항목이 코드에 들어옴)
- 사용자 문구가 한국어가 아니거나, 코드 식별자가 한국어

## Fix-First 기준

```
AUTO-FIX (바로 수정)                      ASK (사람에게 질문)
├─ 죽은 코드, 사용하지 않는 변수           ├─ 보안 (SEC 전부)
├─ N+1 (fetch join 누락)                 ├─ 경쟁 조건, 큐 경계
├─ 코드와 맞지 않는 주석                  ├─ 설계 결정, 결정(D-xx)과 다른 동작
├─ 매직 넘버 → GraffitiProperties         ├─ 20줄 넘는 수정
├─ LLM 응답 형식 검증 누락 (추가만)        ├─ Enum 완전성 (소비자 여러 곳)
├─ 문서의 단순 오기 (경로, 이름)           ├─ 기능 제거, 사용자에게 보이는 동작 변경
└─ 누락된 null/빈 값 가드 (한두 줄)        └─ Gate Integrity (항상)
```

경험칙: **시니어 개발자가 논의 없이 고칠 기계적 수정이면 AUTO-FIX, 의견이 갈릴 수 있으면 ASK.** CRITICAL은 ASK 쪽으로, INFORMATIONAL은 AUTO-FIX 쪽으로 기운다.

## 지적하지 말 것
- 스타일 취향(포매터가 처리하는 것)
- 명세에서 의도적으로 정한 동작 (근거 D-xx가 있는 것)
- diff와 무관한 기존 코드의 문제 (단, 즉시 CRITICAL 항목은 예외로 보고)
