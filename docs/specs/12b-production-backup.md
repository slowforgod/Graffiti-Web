# 12b-production-backup: 운영 공개 준비, 데이터 백업과 복구, 로그와 이미지 정리

- **상태**: DRAFT
- **승인자**: (G1에서 팀원이 기입) / 승인일:
- **브랜치**: `feat/12b-production-backup`
- **선행 명세**: 12a (MERGED), 12c (DRAFT, PR #50. 12c가 APPROVED가 되고 `infra/`가 머지된 뒤에 구현을 시작한다)

## 1. 왜 (Why)
지금 서버의 DB는 EC2 한 대의 Docker 볼륨(`mysql-data`)이 유일한 사본이다. 디스크나 인스턴스가 망가지면 모든 doodle이 사라지고, 보안 감사(2026-10-24)도 SEC-11을 "12b 운영 배포 전 필요"로 보류해 두었다. 컨테이너 로그는 크기 제한이 없어 30GB 디스크를 채우면 MySQL까지 멈출 수 있다. 이 명세는 공개 전에 (1) 매일 백업과 검증된 복구 절차, (2) 백업이 조용히 멈추지 않게 하는 경보, (3) 로그와 이미지가 무한히 쌓이지 않게 하는 정리, (4) 스테이징 서버를 운영으로 올리는 점검표를 갖춘다. 운영 서버는 **스테이징 서버를 승격**해서 쓴다(사람이 확정, 2026-10-28). 별도 운영 서버는 만들지 않는다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-01 | 서버 1대. 이 명세도 서버를 늘리지 않는다 |
| 결정 | D-04 | 월 고정비 최소. 백업은 S3 소액, 별도 운영 서버 없음 |
| 결정 | D-24 | doodle은 MySQL `JSON` 컬럼이라 논리 덤프(`mysqldump`)로 전부 담긴다 |
| 결정 | D-25 | 복구 후 앱이 시작할 때 활성 페이지의 `cellCount`를 다시 계산한다. 복구 리허설에서 확인 |
| 불변 조건 | INV-07 | doodle과 page 행은 삭제하지 않는다. 백업은 이 이력의 사본이다 |
| 불변 조건 | INV-12 | 점유율은 DB에서 재계산 가능해야 한다. 복구 후 재계산이 맞는지 리허설에서 확인 |
| 보안 | SEC-11 | 매일 `mysqldump` → S3, 복구 절차 문서화, 1회 리허설 |
| 보안 | SEC-10 | 3306 비공개, actuator는 `health`만. 공개 전 외부에서 실제로 확인 |
| 보안 | SEC-06 | 백업 스크립트와 저장소에 DB 비밀번호를 쓰지 않는다 |
| 보안 | SEC-13 | 백업에 이메일이 들어 있으므로 버킷 비공개와 암호화. 세션 ID는 백업에서 제외. nginx 오류 로그의 쿼리 문자열 |
| 보안 | SEC-16 | AWS CLI, 사용하는 Actions 버전을 고정 |
| 문서 | `deploy/README.md` §7 | 이미지 태그 롤백. 이 명세의 복구는 데이터 복구로 그것과 다르다 |
| 문서 | 12a 리뷰 INFO-2, INFO-3 | nginx 오류 로그의 쿼리 문자열, GHCR 이미지 보존 정책 |
| 문서 | 12c 명세 | `infra/` CDK 스택에 버킷, 역할, 경보를 더한다 |
| 문서 | `TODOS.md` | "서버 인스턴스 프로파일, SSM 접속, 백업 권한" 중 백업 권한은 이 명세로 옮긴다 |

## 3. 범위
**포함**
- `deploy/backup/backup.sh`: 호스트에서 실행하는 백업 스크립트(순수 셸). `docker compose exec -T mysql`로 `mysqldump`를 실행하고 압축, 무결성 검사, S3 업로드, 성공 지표 전송까지
- 호스트 스케줄: systemd timer 또는 cron으로 매일 1회(UTC 19:00 = KST 04:00, 결정 필요 3)
- S3 버킷(CDK, `infra/`): 비공개, 암호화, TLS 강제, 버전 관리, 보존 기간 후 만료, 스택을 지워도 버킷은 유지(`RETAIN`)
- EC2 인스턴스 프로파일(역할): 버킷의 `mysql/*`에 **`s3:PutObject`만**, 지표 네임스페이스를 한정한 `cloudwatch:PutMetricData`만. 삭제, 조회, 목록 권한 없음
- 백업 감시(CDK): 성공 지표를 하루 단위로 보고, 지표가 없으면 **이상으로 간주**하는 CloudWatch 경보 → SNS 이메일
- `deploy/RESTORE.md`: 복구 절차(최신 백업 찾기 → 내려받기 → 새 MySQL에 복원 → Flyway 검증 → 앱 기동 확인)
- **복구 리허설 1회**(사람 승인 후 실행): 개발 PC의 새 MySQL 컨테이너에 실제 백업을 복원하고 결과를 이 명세의 QA 기록에 남긴다
- 백업-복구 자동 검증: CI에서 임시 MySQL에 행을 넣고 `backup.sh` → 새 MySQL에 복원 → 비교(AWS는 가짜 `aws` 실행 파일로 대체)
- `deploy/compose.yml`: 모든 서비스에 로그 로테이션(`json-file`, 서비스당 크기와 개수 상한)
- `deploy/nginx/nginx.conf`: 속도 제한 거부 로그와 오류 로그 수준을 조정해 쿼리 문자열이 오류 로그에 남지 않게 한다(12a INFO-2)
- `.github/workflows/ghcr-cleanup.yml`: GHCR 이미지 오래된 버전 정리(보존 개수 N, 먼저 dry-run). 12a INFO-3
- `deploy/README.md` 갱신: 운영 승격 절차와 점검표, 백업 스크립트 설치(AWS CLI 설치, 타이머 등록), `STAGING_HOST` 안내 수정(12c 미해결 질문 1 결과 반영), §1.2의 22번 포트 문구
- `docs/ARCHITECTURE.md` §7: 백업 흐름과 새 수치(보존 일수, 로그 상한)를 표로, `CLAUDE.md` §2 운영 URL 기입, `docs/DECISIONS.md`에 백업 결정(D-xx) 추가

**제외 (NOT in scope)** — 이유 한 줄
- 별도 운영 서버, 운영 전용 CDK 스택 → D-04. 필요해지면 12c 스택을 이름만 바꿔 배포(`TODOS.md`)
- 도메인 구매, DNS, Google OAuth 클라이언트, 서버 `.env` 값 → 사람이 한다(§7 점검표). 코드와 비밀값을 이 명세에서 다루지 않는다
- EBS 스냅샷(DLM) → 결정 필요 1에서 mysqldump를 택함. 둘 다 하면 복잡도만 늘어난다
- 다른 리전으로의 백업 복제 → D-04. 리전 장애는 감수한다
- 디스크 사용량 경보(CloudWatch 에이전트) → 로그 상한과 볼륨 크기로 우선 대응. 필요하면 `TODOS.md`
- SSM Session Manager 접속 → `TODOS.md`에 남김
- `STAGING_HOST` 같은 이름을 `HOST`로 바꾸는 일 → 동작에 영향이 없고 12a 명세를 건드림(`TODOS.md`)
- Actions 배포 경로 변경 → 12c 미해결 질문 1. 이 명세는 수동 배포를 전제로 쓴다
- ML 컨테이너 백업, 모델 가중치 → 07b. 가중치는 S3에 이미 있고 DB의 `ml_prediction`은 이 명세의 덤프에 포함된다
- 무중단 배포, 로그 중앙 수집 → 서버 1대(D-01), 범위 밖

## 4. 현재 상태 (검증된 사실)
- 백업 코드와 문서 없음. `deploy/`에는 `app.Dockerfile`, `web.Dockerfile`, `compose.yml`, `nginx/`, `README.md`뿐
- `deploy/compose.yml`: `mysql:8.4.9`, 데이터는 이름 있는 볼륨 `mysql-data`, `MYSQL_ROOT_PASSWORD`는 서버 `.env`에서 컨테이너 환경변수로 들어감. **서비스에 `logging:` 설정 없음**(Docker 기본 `json-file`은 크기 제한이 없다)
- `deploy/nginx/nginx.conf`: `error_log ... warn`, `limit_req_log_level`, `limit_conn_log_level` 설정 없음. 접근 로그는 `$uri`만 기록(SEC-13)
- `.github/workflows/deploy.yml`: `images`가 main마다 GHCR에 `graffiti-app`, `graffiti-web`(태그 = 커밋 SHA)를 올리고 정리하는 단계는 없음. `staging` 단계는 `docker image prune -f`만 서버에서 실행
- DB: Flyway 마이그레이션 V0~V5. `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`는 V2가 만든다. `doodle.payload`는 `JSON`, `page.final_occupancy_bitmap`은 `VARBINARY(4096)`
- 보안 감사 `docs/security-audits/2026-10-24.md`: SEC-10은 "보안 그룹, 3306 비공개는 12b 배포 점검", SEC-11은 "백업과 복구 리허설 없음", SEC-13은 "예외 스택에 이메일이 섞일 수 있는지 운영 로그 확인 필요(결정 3)"와 "동의서 보관 기간 문구는 학교 확인 대기"
- `docs/ARCHITECTURE.md` 다이어그램에 "매일 mysqldump → S3 (SEC-11)"이 있고 §7이 백업을 12b에서 정한다고 적어 둠
- 12c 명세(DRAFT): 인스턴스 프로파일 없음, 보안 그룹의 22는 팀원 CIDR만, IMDSv2 필수

## 5. 설계

```
호스트 (EC2, systemd timer 매일 UTC 19:00)  ─ flock으로 중복 실행 방지
  backup.sh
   1. df로 여유 공간 확인 (부족하면 실패)
   2. docker compose exec -T mysql  mysqldump --single-transaction   ← 비밀번호는 컨테이너 안 환경변수, 호스트 프로세스 목록에 안 나옴
        ├─ graffiti 전체 (SPRING_SESSION* 데이터 제외)
        └─ SPRING_SESSION* 는 구조만 (복원 후에도 Flyway 검증이 통과해야 하므로)
      → gzip → 임시 파일
   3. 검증: 종료 코드, gzip -t, 크기 > 0, 덤프 끝의 "Dump completed" 줄
   4. aws s3 cp  → s3://<bucket>/mysql/YYYY/MM/DD/graffiti-<UTC시각>.sql.gz   (역할: PutObject만)
   5. aws cloudwatch put-metric-data  BackupSuccess=1   (4까지 성공했을 때만)
   6. 임시 파일 삭제
  실패하면 5를 하지 않는다 → 하루 동안 지표가 없음 → 경보 → SNS 이메일

복구 (사람, 개인 AWS 자격 증명)
  RESTORE.md: S3에서 최신 객체 → 새 mysql:8.4.9 컨테이너에 복원 → 앱 기동 → Flyway 통과, cellCount 재계산(D-25) 확인

CDK (12c의 infra/ 스택에 추가)
  S3 버킷(비공개, SSE, TLS 강제, 버전 관리, N일 후 만료, RETAIN) + 인스턴스 역할 + 경보 + SNS
  IMDSv2 홉 제한은 기본값(1) 유지 → 컨테이너는 역할 자격 증명에 닿지 못하고 호스트만 쓴다
```

- 이 명세에는 `PageCommandProcessor` 큐 안에서 일어나는 일이 없다(INV-01, INV-02 해당 없음). 백업은 큐 밖의 읽기 전용 작업이고 `--single-transaction`이라 쓰기를 막지 않는다
- **Search Before Building**
  - EBS 스냅샷 자동화(Amazon Data Lifecycle Manager)가 이미 있다. SEC-11도 "S3 또는 EBS 스냅샷"을 허용한다. 스냅샷은 코드가 거의 없지만 복구가 "볼륨을 새로 만들어 교체"라서 리허설이 무겁고 DB 버전이 바뀌면 쓰기 어렵다. 논리 덤프는 어느 MySQL로든 복원돼서 리허설을 개발 PC에서 할 수 있다 → 결정 필요 1
  - S3 수명 주기, 버전 관리, 암호화, TLS 강제는 S3 기본 기능이다. 직접 만들 것이 없다
  - IMDSv2를 필수로 하면 컨테이너 안에서 메타데이터를 받으려면 홉 제한이 2 이상이어야 한다. 확인한 근거는 AWS 문서 검색에서 나온 Image Builder 설정 설명("토큰이 필요하면 컨테이너 이미지 빌드는 최소 2홉")뿐이고, 런타임 컨테이너에 대한 EC2 문서 문구는 아직 읽지 못했다(미검증). 그래서 백업을 컨테이너가 아닌 호스트에서 돌리는 쪽으로 설계했다(앱 컨테이너가 침해돼도 서버 역할을 못 쓴다). 구현할 때 EC2 문서와 AC-9 실측으로 확인한다
  - GHCR 정리는 GitHub의 `actions/delete-package-versions` 액션이 있는지 구현 때 확인한다(없거나 맞지 않으면 `gh api` 호출로 대체). 공식 출처와 버전을 명세에 기록한 뒤 SHA로 고정한다
  - `mysqldump`의 세션 테이블 처리: MySQL 8.4의 `--ignore-table`은 데이터와 구조를 함께 제외하므로, 구조만 따로 덤프하는 두 번째 호출이 필요하다. 구현 때 mysql:8.4.9 이미지로 확인한다
- 새 구성 요소: 셸 스크립트 1, 타이머 설정 1, 복구 문서 1, 워크플로 1(GHCR 정리), CDK 리소스 4종(버킷, 역할, 경보, SNS). 스크립트를 컨테이너로 만들면 홉 제한을 풀어야 해서 호스트 스크립트가 가장 적다

| 파일 (예정) | 역할 |
|---|---|
| `deploy/backup/backup.sh` | 덤프, 검증, 업로드, 지표. `set -euo pipefail`, `flock`, 임시 파일, UTC 시각 |
| `deploy/backup/graffiti-backup.service`, `.timer` | 매일 실행 (systemd). 실패하면 서비스가 실패 상태로 남음 |
| `deploy/backup/test/backup_restore_test.sh` | CI용 검증: 임시 MySQL에 행 삽입 → `backup.sh` → 새 MySQL에 복원 → 행 수와 Flyway 이력 비교, 세션 테이블이 비어 있음, 실패 경로 |
| `deploy/backup/test/fake-aws` | 테스트에서 `aws`를 대신하는 스크립트(로컬 폴더로 복사, 실패 모드 지원) |
| `deploy/RESTORE.md` | 복구 절차와 리허설 기록 양식 |
| `deploy/compose.yml` | 서비스별 `logging` 상한 |
| `deploy/nginx/nginx.conf` | 오류 로그 수준, `limit_req_log_level`, `limit_conn_log_level` |
| `.github/workflows/ghcr-cleanup.yml` | 주 1회와 수동 실행, `dry-run` 입력, 보존 개수 N |
| `.github/workflows/ci.yml` | `backup` 작업(셸 검사와 위 테스트), compose 로그 설정 검사 |
| `infra/lib/graffiti-staging-stack.ts` | 버킷, 역할, 경보, SNS 추가(12c 파일 수정) |
| `infra/test/*` | 새 리소스의 템플릿 단언 |
| `deploy/README.md`, `docs/ARCHITECTURE.md` §7, `docs/DECISIONS.md`, `CLAUDE.md` §2 | 문서 갱신 |

- 앱 설정값(`GraffitiProperties`) 추가 없음(INV-10). 인프라 수치(보존 일수, 로그 상한, 실행 시각)는 `deploy/`와 `infra/lib/`에 한 곳씩 두고 `ARCHITECTURE.md` §7 표에 적는다
- **결정 필요 1 — 백업 방식**: **추천: `mysqldump` → S3.** 이유: 복원이 어디서나 되고 리허설이 가볍다, 비용이 매우 작다. 대안: EBS 스냅샷(DLM) — 코드는 적으나 복구가 무겁다
- **결정 필요 2 — 실행 위치**: **추천: 호스트의 systemd timer.** 이유: IMDSv2 홉 제한 1을 유지해 앱 컨테이너가 서버 역할을 쓰지 못한다(SEC-10의 정신). 대안: compose 사이드카 컨테이너 — 홉 제한을 2로 풀어야 해서 앱 컨테이너도 역할 자격 증명에 닿는다
- **결정 필요 3 — 주기, 보존, 허용 손실**: **추천: 매일 1회(UTC 19:00), 14일 보존, 복구 시점 목표 24시간.** 이유: 페이지 수명이 최대 7일이고 낙서는 다시 그릴 수 있어 하루 손실은 감수 가능하다고 본다. 팀이 하루가 길다고 하면 주기를 줄인다(스크립트는 같고 타이머만 바뀜)
- **결정 필요 4 — 백업 감시**: **추천: CloudWatch 사용자 지정 지표 + 경보(지표 없음 = 이상) + SNS 이메일.** 이유: 백업 실패와 타이머가 안 돈 경우를 모두 잡는다. 대안: 외부 하트비트 서비스(새 계정과 외부 의존), 감시 없음(조용한 실패라 critical gap)
- **결정 필요 5 — nginx 오류 로그**: **추천: `error_log ... crit`와 `limit_req_log_level`, `limit_conn_log_level`을 `notice`로.** 이유: 업스트림 연결 실패 같은 오류 줄은 요청 줄(쿼리 문자열 포함)을 남기므로 수준을 올려야 OAuth `code`가 남지 않는다. 장애 진단은 접근 로그의 상태 코드와 앱 로그로 한다. 대안: `error` 유지(진단은 쉽지만 업스트림 오류 줄에 쿼리 문자열이 남을 수 있음)
- **결정 필요 6 — 로그 상한**: **추천: 서비스당 `max-size` 10MB, `max-file` 3.** 이유: 서비스 4개에 최대 120MB로 디스크 30GB에 영향이 없다. 07b에서 ML 컨테이너가 추가되면 같은 설정을 붙인다
- **결정 필요 7 — GHCR 보존**: **추천: 각 이미지의 최근 20개 버전 유지, 나머지 삭제.** 이유: 롤백(README §7)이 최근 SHA 몇 개면 충분하다. 최초 실행은 `dry-run`

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| 덤프 | MySQL 컨테이너가 내려가 있음, 덤프 중간에 실패 | `pipefail`로 스크립트 실패, 업로드와 성공 지표를 하지 않음(부분 파일이 S3에 올라가지 않도록 **임시 파일에 먼저 쓰고 검증 후 업로드**) | `backup_restore_test.sh` (MySQL을 멈추고 실행 → 종료 코드 ≠ 0, 업로드 없음) |
| 덤프 | 디스크 부족 | 시작 전 여유 공간 확인에서 실패, 임시 파일은 항상 삭제(`trap`) | 테스트: 여유 공간 확인 함수(작은 임계값으로) |
| 덤프 | 잘린 gzip, 크기 0 | 검증 단계에서 실패 | 테스트: 잘린 파일을 만들어 검증이 거부 |
| 업로드 | 자격 증명 오류, 네트워크 끊김 | AWS CLI 재시도 후 실패 → 성공 지표 없음 → 하루 뒤 경보 메일 | 테스트: `fake-aws`가 실패하면 종료 코드 ≠ 0이고 지표 호출 없음 |
| 스케줄 | 타이머가 등록되지 않았거나 서버가 꺼져 있음 | 지표가 없으므로 경보(지표 없음 = 이상) | QA: 타이머를 멈추고 경보 상태 확인(`describe-alarms`) |
| 스케줄 | 이전 실행이 끝나기 전에 다음 실행 | `flock`으로 두 번째 실행은 즉시 종료 | 테스트: 동시 두 번 실행 |
| 감시 | 경보 메일 수신자가 SNS 구독을 확인(confirm)하지 않음 | 확인 전에는 메일이 오지 않으므로 점검표에 확인 단계를 넣고 구독 상태를 AWS로 확인 | QA: `list-subscriptions-by-topic`에서 확인됨 |
| 보안 | 백업에 이메일이 있고 버킷이 노출됨 | 공개 접근 전체 차단, 암호화, TLS 강제, 접근은 역할 `PutObject`와 사람의 관리자 자격 증명뿐 | CDK 템플릿 단언 |
| 보안 | 서버가 침해되어 백업을 지우거나 덮어쓰려 함 | 역할에 삭제, 조회 권한이 없고 버전 관리가 켜져 있어 덮어쓰기도 이전 버전이 남음 | CDK 템플릿 단언(허용 동작이 정확히 `PutObject`), QA: 서버 자격 증명으로 삭제 시도가 거부됨 |
| 보안 | 세션 ID가 백업에 섞임 | 세션 테이블은 구조만 덤프 | 테스트: 복원한 DB의 `SPRING_SESSION`이 비어 있음 |
| 보안 | 앱 컨테이너가 서버 역할 자격 증명을 읽음 | 홉 제한 1이라 컨테이너에서 메타데이터 요청이 시간 초과 | QA: 앱 컨테이너에서 메타데이터 주소로 요청이 실패 |
| 복구 | 덤프가 복원되지 않음(문자셋, 정의자, GTID), 복원 후 Flyway 검증 실패 | 리허설과 CI 검증이 먼저 잡는다. 복원본으로 앱을 띄워 `cellCount`가 DB에서 재계산되는지 확인 | CI 복원 테스트, 리허설 |
| 복구 | 복구 중 운영 DB를 덮어씀 | `RESTORE.md`는 **새 컨테이너에 복원하는 것이 기본**이고, 운영 볼륨 교체는 별도 절차와 사람 승인 | 문서 점검 |
| 로그 | 컨테이너 로그가 디스크를 채움 | 서비스당 크기 상한과 회전 | CI: `docker compose config`로 네 서비스 모두 `max-size`와 `max-file`이 있음 |
| 로그 | nginx 오류 로그에 OAuth `code`가 남음 | 수준을 `crit`로, 속도 제한 거부 로그를 `notice`로 | QA: app을 멈춘 채 `?code=SECRET123&state=x` 요청 후 web 로그에 그 문자열이 없음(12a QA와 같은 방식) |
| 이미지 | 정리가 롤백에 필요한 이미지나 배포 중인 이미지를 지움 | 최근 N개는 지우지 않음, 처음에는 `dry-run`으로 목록만 확인 | 워크플로 `dry-run` 출력 확인, `actionlint` |
| 운영 전환 | 공개 전 DB 초기화(`down -v`)로 데이터 유실 | 백업을 먼저 만들고 사람이 승인. 스테이징 데이터는 버려도 된다(12a §10)는 전제에서만 | 점검표 항목 |
| 운영 전환 | OAuth 리디렉션 URI 누락 | 로그인 실패. 점검표에서 운영 도메인을 등록하고 시험 로그인 | QA(사람) |
| 시간 | 서버 시간대에 따라 백업 날짜가 바뀜 | 모든 시각을 UTC로 기록(`date -u`, 타이머도 UTC) | 테스트: 객체 키가 UTC 형식 |

critical gap: 0 (경보 수신자가 구독을 확인하지 않는 경우는 점검표와 QA 항목으로 막는다. 리허설은 서버가 아니라 개인 PC에서 하므로 서버 안의 복원 경로 자체는 검증하지 못한다. 이 한계는 §8에 남긴다)

## 7. 수용 기준 (테스트 가능하게)
- [ ] AC-1 (SEC-11): Given 운영 서버 When 타이머가 한 번 실행되면 Then `s3://<bucket>/mysql/YYYY/MM/DD/` 아래에 비어 있지 않은 `.sql.gz` 객체가 생기고 `BackupSuccess` 지표가 1 늘어난다
- [ ] AC-2: Given 백업 파일 When 새 `mysql:8.4.9`에 복원하고 앱을 띄우면 Then Flyway 검증이 통과하고 `users`, `page`, `doodle`의 행 수가 백업 시점과 같고 활성 페이지의 `cellCount`가 DB에서 재계산된다(D-25, INV-12)
- [ ] AC-3 (SEC-13): Given 복원한 DB When `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`를 조회하면 Then 둘 다 비어 있다
- [ ] AC-4: Given MySQL이 내려가 있음 또는 `aws`가 실패함 When `backup.sh`를 실행하면 Then 종료 코드가 0이 아니고 성공 지표가 전송되지 않으며 임시 파일이 남지 않는다
- [ ] AC-5: Given 이전 실행이 아직 도는 중 When `backup.sh`를 또 실행하면 Then 두 번째는 아무것도 하지 않고 종료한다
- [ ] AC-6: Given 하루 동안 `BackupSuccess` 지표가 없음 When 경보를 확인하면 Then 상태가 이상이고 SNS 구독자에게 메일이 간다
- [ ] AC-7 (SEC-13, SEC-10 거부): Given 버킷 When 설정을 보면 Then 공개 접근이 전부 차단되고, 서버 측 암호화가 켜져 있고, TLS가 아닌 요청이 거부되고, 버전 관리가 켜져 있고, 만료 규칙이 있고, 스택 삭제 시 유지된다
- [ ] AC-8 (최소 권한 거부): Given 인스턴스 역할 When 정책을 보면 Then 허용 동작은 `mysql/*`에 대한 `s3:PutObject`와 지정 네임스페이스의 `cloudwatch:PutMetricData`뿐이고, 서버 자격 증명으로 객체 삭제와 목록 조회를 시도하면 거부된다
- [ ] AC-9 (SEC-10): Given 앱 컨테이너 안 When 인스턴스 메타데이터 주소로 요청하면 Then 시간 초과로 실패한다(홉 제한 1)
- [ ] AC-10 (SEC-06): Given `deploy/backup/`와 저장소 When 검사하면 Then DB 비밀번호와 AWS 키가 없고 `backup.sh`가 비밀번호를 호스트 명령줄에 출력하지 않는다(gitleaks, 프로세스 목록 확인)
- [ ] AC-11: Given `deploy/compose.yml` When `docker compose config`를 보면 Then 네 서비스 모두 `json-file`의 `max-size`와 `max-file`이 있다
- [ ] AC-12 (SEC-13): Given app이 내려가 있음 When `/login/oauth2/code/google?code=SECRET123&state=x`를 요청하면 Then web 접근 로그와 오류 로그 어디에도 `SECRET123`이 없다
- [ ] AC-13 (SEC-10, 운영 점검): Given 운영 도메인 When 외부 네트워크에서 3306과 8080에 접속하면 Then 시간 초과이고 `/actuator/env`와 `/actuator`는 404, `/actuator/health`만 UP이다
- [ ] AC-14: Given GHCR 정리 워크플로 When `dry-run`으로 실행하면 Then 삭제 대상 목록만 출력하고 실제 삭제는 하지 않으며, 실제 실행은 각 이미지의 최근 N개를 남긴다
- [ ] AC-15 (SEC-11 기록): Given 복구 리허설 When 완료하면 Then 날짜, 사용한 백업 객체 키(내용 없음), 걸린 시간, 확인 항목(AC-2, AC-3)이 이 명세의 QA 기록에 남는다
- [ ] AC-16 (SEC-16): Given 새로 쓰는 도구와 액션 When 보면 Then AWS CLI와 Actions 버전이 고정돼 있다

**운영 공개 전 점검표** (사람이 체크, 코드로 하지 않는다. `deploy/README.md` §8로 옮긴다)
- [ ] 도메인 구매와 DNS A 레코드(Elastic IP), 서버 `DOMAIN`, `LETSENCRYPT_EMAIL`
- [ ] Google OAuth 리디렉션 URI에 운영 도메인 추가, 시험 로그인(`@khu.ac.kr` 계정과 아닌 계정)
- [ ] 서버 `.env`의 `ADMIN_EMAILS` 확정, `MODERATION_UNCHECKED`가 `HOLD`(기본)인지 확인(`PUBLISH`는 공개 서버에서 금지)
- [ ] Let's Encrypt 인증서 발급, `https://<도메인>/actuator/health` UP, http → https 리다이렉트
- [ ] 백업 첫 실행 확인(AC-1), 경보 구독 확인(SNS 메일의 confirm 클릭), 복구 리허설 완료(AC-15)
- [ ] `/gf-security` 전체 감사(SEC-01~17) 통과, 특히 SEC-10(AC-13), SEC-11, SEC-13의 운영 로그 확인(예외 스택에 이메일이 섞이는지)
- [ ] 동의서의 보관 기간 문구를 학교에 확인(SEC-13 보류 항목)
- [ ] 공개 전 DB 초기화 여부 결정(초기화한다면 백업을 먼저 만들고 사람이 승인)
- [ ] `CLAUDE.md` §2의 운영 URL 기입, 비용 알림(예산) 설정 여부 결정

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 셸 정적 검사 | `backup.sh` | CI의 `shellcheck` |
| 통합(Docker, CI) | 임시 MySQL에 행 삽입 → `backup.sh`(가짜 `aws`) → 새 MySQL 복원 → 행 수, Flyway 이력, 세션 테이블 비어 있음. 실패 경로(MySQL 중지, `aws` 실패, 잘린 gzip, 동시 실행, 디스크 부족) | `deploy/backup/test/backup_restore_test.sh` |
| 설정 검사(CI) | `docker compose config`에 네 서비스의 로그 상한, nginx 설정 검사(`nginx -t`) | CI 작업 |
| CDK 템플릿 단언 | 버킷 설정, 역할 정책(정확히 허용 동작만), 경보(지표 없음 = 이상), SNS, RETAIN. 허용 동작을 하나 늘리면 실패해야 한다 | `infra/test/` |
| QA 시나리오(실제 서버) | 백업 첫 실행과 객체 확인(AC-1), 서버 자격 증명으로 삭제 거부(AC-8), 컨테이너에서 메타데이터 접근 실패(AC-9), 타이머를 멈추고 경보 확인(AC-6), 외부에서 3306 접속(AC-13), `code` 로그 확인(AC-12) | 수동 체크리스트 |
| 리허설(개인 PC, 사람 승인) | 실제 백업을 내려받아 복원, 앱 기동 확인(AC-2, AC-3, AC-15) | `deploy/RESTORE.md` 양식 |
| 미검증으로 남김 | 서버 안에서의 복원 경로(리허설은 개인 PC), 리전 장애, 장기간 방치 시 비용 | — |

- 회귀를 잡지 못하는 테스트는 만들지 않는다: 복원 테스트는 백업 스크립트가 만든 파일을 **다른 MySQL에 복원해서 값을 비교**하고, 스크립트의 검증을 일부러 빼면(잘린 파일) 실패하는 것을 구현 중 한 번 확인한다

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 이 명세의 범위만 구현한다. 범위 밖이 필요해 보이면 멈추고 질문(NEEDS_CONTEXT)
- **`cdk deploy`, 서버에서의 `docker compose`, 백업 실행, 복구 리허설은 사람의 승인 없이 하지 않는다.** CDK는 항상 `cdk diff`를 먼저 보여 준다
- 서버에서 `docker compose down -v`, 볼륨 삭제, 운영 DB 덮어쓰기를 하지 않는다. 복구 문서의 기본은 새 컨테이너 복원이다
- `.env`를 읽거나 쓰지 않는다. DB 비밀번호와 AWS 키를 스크립트, 문서, 로그에 쓰지 않는다. 비밀번호는 컨테이너 안의 환경변수로만 쓴다(SEC-06)
- 인스턴스 역할에 `s3:*`, `s3:GetObject`, `s3:DeleteObject`, `s3:ListBucket`을 넣지 않는다. IMDSv2 홉 제한을 2로 올리지 않는다(결정 필요 2). 완화가 필요해 보이면 멈춘다
- 버킷의 `RETAIN`과 공개 접근 차단, 암호화, TLS 강제를 약화하지 않는다(Gate Integrity)
- 테스트 삭제, `@Disabled` 대응물(`skip`), 검증 단계 제거, `set +e`, 빈 오류 처리로 테스트를 통과시키지 않는다
- GHCR 정리는 `dry-run`을 기본값으로 만들고 처음 실제 실행은 사람이 승인한다
- 새 액션은 SHA로 고정하고(SEC-16) 의존성 추가는 최소로 한다. 개인 이메일 주소, 계정 ID, 버킷 실제 이름을 소스와 문서에 쓰지 않는다(CDK context 또는 출력으로만)
- 12a의 `deploy.yml` 배포 로직은 고치지 않는다. 12c 미해결 질문 1이 정해진 뒤에 `README` 안내만 맞춘다

## 10. 롤백
- 백업 타이머만 끄면 백업이 멈춘다(`systemctl disable --now graffiti-backup.timer`). 서버 자체에는 영향이 없다
- CDK 변경은 이전 커밋으로 `cdk diff` 확인 후 되돌린다. 버킷은 `RETAIN`이라 지워지지 않고 백업 데이터가 남는다
- compose 로그 설정과 nginx 설정은 이전 커밋 SHA로 `up -d`로 되돌린다(`deploy/README.md` §7)
- GHCR 정리는 **지운 이미지를 되돌릴 수 없다.** 그래서 `dry-run`이 기본이고 보존 개수 N을 넉넉히 둔다. 필요하면 같은 커밋을 다시 빌드한다
- DB 마이그레이션 없음

---

## 미해결 질문
1. **복구 시점 목표와 보존 일수**: 매일 1회, 14일 보존(RPO 24시간)이 팀이 허용하는 범위인지(결정 필요 3). 하루 손실이 크다고 하면 주기를 줄인다
2. **경보 메일 수신자**: 누가 받을지. 개인 이메일은 저장소에 쓰지 않고 `cdk deploy -c`로 넘긴다(SEC-06). 구독 확인(메일의 confirm 클릭)은 수신자가 해야 한다
3. **12c 미해결 질문 1(Actions 배포 경로)** 결과에 따라 `deploy/README.md` §4의 안내와 이 명세의 "수동 배포" 전제가 바뀔 수 있다
4. **동의서 보관 기간 문구**: 학교 확인 대기(SEC-13 보류 항목, 사람)
5. **공개 전 DB 초기화**: 스테이징에서 쌓인 테스트 데이터를 지울지(사람이 결정, 백업 후 승인)

---

## 리뷰 기록 (/gf-review가 추가)
<!-- 날짜 · 커밋 SHA · CRITICAL n / INFO n · AUTO-FIX n · ASK 결과 · 상태 -->

## QA 기록 (/gf-qa가 추가)
<!-- 날짜 · 커밋 SHA · 시나리오 통과/실패 · 발견 버그(ISSUE-NNN) · 상태 -->

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
