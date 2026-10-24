# 11-security-pass: 공개 전 보안 점검과 수정

- **상태**: MERGED (PR #43, 2026-10-24)
- **범위**: `docs/SECURITY.md` SEC-01 ~ SEC-17 점검, 관리자 API 속도 제한(M4에서 미룸), 관리자 경로 단위 권한 확인
- **제외**: 12b 운영 배포 점검(보안 그룹, 백업), 실제 07 검사기, 차단 시 WebSocket 종료(10 나머지)

## 1. 변경
- `ApiGuards.admin()`: `/api/admin/**` 전체에 인터셉터. DB에서 현재 사용자를 읽어 `AccessCheck`로 ADMIN 확인 후 관리자당 초당 `graffiti.web.admin-requests-per-second`(기본 20)회.
- `ApiGuards.writes()`: 관리자 경로를 뺀 `/api/**`의 POST/PUT/PATCH/DELETE를 로그인 사용자당 초당 `write-requests-per-second`(기본 10)회. 비로그인 요청은 Spring Security가 먼저 막음.
- 넘으면 429 `RATE_LIMITED`(`docs/API.md`). 창은 `SendRateLimiter`와 같은 1초 고정 창.

## 2. 테스트
- `ApiGuardsIT`: 핸들러가 `@CurrentUser`를 받지 않아도 관리자 경로가 403, 관리자 한도, 변경 요청 한도.
- 다른 통합 테스트는 한도를 크게 잡아 영향 없음(`IntegrationTest`).

## 3. 감사 기록
- `docs/security-audits/2026-10-24.md`

## 9. 결정 (2026-10-24 현 확정: 전부 추천안)
1. 한도 값 20/10: 관리자 화면 한 번 갱신과 일괄 삭제 연타를 넉넉히 덮는 값. 바꾸면 `application.yml` 두 줄.
2. 1초 고정 창: STOMP와 같은 방식. 경계에서 두 배까지 허용되는 점은 감수.
