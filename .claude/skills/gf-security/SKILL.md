---
name: gf-security
model: claude-opus-5-5
effort: medium
description: graffiti 배포 전 보안 감사. docs/SECURITY.md의 SEC-01~17을 코드, 설정, 테스트 기준으로 하나씩 점검하고, 규칙마다 증거(파일:줄, 테스트 이름, 실행 결과)를 남긴다. 첫 공개 배포 전, 그리고 인증·권한·모더레이션·실시간 인터셉터를 바꾼 변경을 배포하기 전에 사용. "보안 점검", "security audit", "배포 전 보안 확인"에 사용.
---

# /gf-security — SEC-01~17 감사

입력: `$ARGUMENTS` (선택: 특정 SEC ID 목록, 또는 명세 번호)

## 원칙
- **증거 없는 "안전함"은 없다.** 규칙마다 판정 근거를 파일:줄이나 테스트 실행 결과로 남긴다.
- 확인할 수 없는 항목은 `UNVERIFIED`로 표시한다. 통과로 간주하지 않는다.
- 감사 중에는 코드를 고치지 않는다. 수정은 감사가 끝난 뒤 항목별로 승인받아 진행한다.

## 단계

### 1. 범위
- 인자가 없으면 SEC-01~17 전체. 명세 번호가 있으면 그 명세의 근거 문서 표에 있는 SEC + SEC-02, SEC-07(항상 포함).
- `docs/SECURITY.md`를 읽는다.

### 2. 공격 표면 목록
- REST 엔드포인트 전체: `@RequestMapping`, `@GetMapping` 등을 찾아 경로, 메서드, 권한을 표로 만든다.
- STOMP: `@MessageMapping`, 구독 가능한 목적지, `ChannelInterceptor`의 검사 항목.
- 외부 호출: OAuth, ChatKHU, OpenAI (타임아웃, 재시도, 응답 파싱).
- ML 서버: `ml/serving`의 엔드포인트, Docker 포트 공개 여부, 로그에 OCR 글자나 이미지가 남는지, 학습 데이터 추출 스크립트에 작성자 정보가 섞이는지 (SEC-17).
- 설정: `SecurityFilterChain`, CORS와 Origin, 세션과 쿠키, actuator 노출, 로그 패턴.
- 인프라 파일: `docker-compose.yml`(포트 공개), nginx 설정, GitHub Actions(시크릿 사용), `.gitignore`.
- `API.md`에 없는 엔드포인트가 코드에 있으면 그 자체로 발견 사항이다.

### 3. 규칙별 점검
SEC마다 다음을 기록한다.
```
SEC-04 학교 도메인 우회 — PASS | FAIL | PARTIAL | UNVERIFIED
근거: backend/.../DomainVerifier.java:31 (hd, email_verified, @khu.ac.kr 접미사 모두 검사)
테스트: DomainVerifierTest.rejects_lookalike_domain — 실행 결과 통과
발견: (없음 | 내용과 위험도)
```
- 테스트가 있다고 적혀 있으면 **실제로 실행해서** 통과를 확인한다.
- 각 SEC의 "증명할 테스트"가 없으면 PARTIAL로 판정한다.
- 확인 명령 예시:
  - 시크릿: `git log -p | grep -iE "api[_-]?key|secret|password" | head` (값은 출력하지 말고 위치만 기록)
  - 포트 공개: `docker-compose.yml`의 `ports:` 확인 (mysql에 없어야 함)
  - 로그: `log.(info|debug|warn).*(email|token|session)` 패턴 검색

### 4. 독립 검증
- FAIL이나 PARTIAL 판정은 한 번 더 확인한다: 정말 도달 가능한 경로인가? 다른 계층(nginx, 필터)에서 이미 막히는가?
- 확신도(1~10)를 붙인다. 확신이 낮아도 지우지 않고 별도 목록에 남긴다.

### 5. 보고와 기록
- 요약 표: SEC별 판정, 발견 사항 수(위험도별).
- 기록 위치: 명세 번호가 있으면 그 명세의 `## 보안 감사 기록`, 전체 감사면 `docs/security-audits/YYYY-MM-DD.md`.
- 기록에 감사한 커밋 SHA를 포함한다.
- 수정이 필요한 항목은 하나씩 질문한다(추천 포함): A) 지금 수정 B) 배포 차단하고 별도 명세로 C) 위험 수용(이유 기록, 사람 승인 필요)

## 완료 상태
- `DONE` — 범위 내 전부 PASS
- `DONE_WITH_CONCERNS` — FAIL 없음, PARTIAL이나 UNVERIFIED 있음 (목록)
- `BLOCKED` — 높음(🔴) 등급 FAIL 존재 → **배포 불가**
