---
name: gf-deploy-check
description: graffiti 배포 후 확인. GitHub Actions 배포가 끝난 뒤 운영 환경의 헬스체크, 정적 파일, WebSocket 연결, 공개 API, 보안 헤더와 노출 포트를 점검하고 결과를 명세에 기록한다. 사용자가 명시적으로 /gf-deploy-check를 호출할 때만 사용.
disable-model-invocation: true
---

# /gf-deploy-check — 배포 후 확인

운영 URL과 헬스체크 경로는 `CLAUDE.md` §2 "배포 설정"에서 읽는다. 비어 있으면 사용자에게 묻고, 답을 `CLAUDE.md`에 기록한다.

## 절대 규칙
- **운영 데이터를 바꾸는 요청을 보내지 않는다.** 게시, 삭제, 관리자 API 호출 금지. 읽기 전용 확인만.
- 운영 서버에 SSH로 접속하거나 컨테이너를 재시작하는 명령은 **사용자 승인 후에만** 실행한다.
- 이메일, 토큰, 쿠키 값을 출력하거나 기록하지 않는다.

## 단계

### 1. 배포 상태
- 최근 Actions 실행을 확인한다: `gh run list --branch main --limit 3`
- 실패했으면 로그 요약을 보여주고 멈춘다(`gh run view <id> --log-failed`).
- 배포된 커밋 SHA를 기록한다.

### 2. 스모크 점검
| 항목 | 방법 | 기대 |
|---|---|---|
| 헬스체크 | `curl -s -o /dev/null -w "%{http_code}" https://<도메인>/actuator/health` | 200, 본문에 상세 정보 없음 |
| 정적 파일 | `curl -sI https://<도메인>/` | 200, HTTPS |
| HTTP → HTTPS | `curl -sI http://<도메인>/` | 301/308 |
| 현재 페이지 | `curl -s https://<도메인>/api/pages/current` | 200, `status`와 `scheduledEndAt` 존재 |
| WebSocket | STOMP CONNECT → `/topic/page.{id}.meta` 구독 성공 (브라우저나 간단한 스크립트) | 연결 성공 |
| 잘못된 Origin | `Origin: https://evil.example`로 `/ws` 연결 | 거절 (SEC-03) |
| actuator 노출 | `/actuator/env`, `/actuator/beans` | 404 (SEC-10) |
| DB 포트 | 외부에서 `nc -z -w 3 <도메인> 3306` | 연결 안 됨 (SEC-10) |
| 보안 헤더 | `curl -sI`의 `Strict-Transport-Security` 등 | 존재 |

### 3. 화면 확인 (가능하면)
- 내장 브라우저로 운영 URL을 열어 캔버스 로딩, 미니맵, 상단 상태바가 보이는지 확인한다. 콘솔 에러를 확인한다.
- 로그인이 필요한 확인은 사람에게 넘긴다.

### 4. 기록
- 해당 명세들의 `## 배포 기록`에 추가한다:
  `YYYY-MM-DD · 배포 SHA · 스모크 n/n 통과 · 실패 항목 · 상태`
- 모두 통과하면 사용자에게 명세 상태를 `DONE`으로 바꿔도 된다고 알린다.
- 실패가 있으면 위험도를 판단해서 제안한다: A) 이전 버전으로 되돌리기(직전 이미지로 재배포) B) 핫픽스 브랜치 C) 관찰. 되돌리기는 사람이 승인해야 실행한다.

## 완료 상태
- `DONE` — 전부 통과
- `DONE_WITH_CONCERNS` — 핵심(헬스, 페이지, WebSocket)은 통과, 부가 항목 실패
- `BLOCKED` — 헬스체크, 현재 페이지, WebSocket 중 하나라도 실패
