# 12c-aws-infra: 스테이징 EC2 인프라를 CDK로 정의 (EC2, 보안 그룹, Elastic IP)

- **상태**: DRAFT
- **승인자**: (G1에서 팀원이 기입) / 승인일:
- **브랜치**: `feat/12c-aws-infra`
- **선행 명세**: 12a

## 1. 왜 (Why)
12a는 서버 준비를 "사람이 `deploy/README.md` 순서대로" 하게 두었다(12a §3 제외). 콘솔 클릭으로 만든 EC2와 보안 그룹은 SEC-10(80/443만, SSH는 팀원 IP만)이 지켜지는지 코드로 검토할 수 없고, 지우거나 다시 만들 때 재현되지 않는다. 이 명세는 스테이징 서버의 AWS 리소스만 CDK 코드로 정의해서 PR에서 보안 그룹 규칙을 리뷰하고, 같은 구성을 12b(운영)에서 재사용할 수 있게 한다. 도메인, 인증서, 비밀값은 도메인이 정해진 뒤의 일이라 이 명세 밖이다.

## 2. 근거 문서
| 종류 | ID | 이 명세와의 관계 (한 줄) |
|---|---|---|
| 결정 | D-01 | 서버 1대. 오토스케일링, 로드밸런서를 만들지 않는다 |
| 결정 | D-02 | EC2 1대에 Docker로 배포. 이 명세는 EC2까지만 만들고 compose 구성은 12a 그대로 |
| 결정 | D-04 | 사비 운영, 월 고정비 최소. 새 VPC, NAT 게이트웨이, 로드밸런서 없음 |
| 결정 | D-36 | 메모리 4GB급 인스턴스 (ML 컨테이너는 07b에서 추가) |
| 보안 | SEC-10 | 80/443 공개, **22는 팀원 IP만**, 3306/8080 등 그 밖의 포트는 열지 않음 |
| 보안 | SEC-06 | 비밀값과 팀원 IP를 저장소에 넣지 않는다. 키 쌍의 **공개 키**만 코드와 설정에 쓴다 |
| 보안 | SEC-16 | CDK와 npm 의존성 버전 고정, Dependabot 항목 추가 |
| 아키텍처 | `ARCHITECTURE.md` §7 | 배포 구성의 EC2 부분이 이 명세의 산출물과 맞아야 한다 |
| 문서 | `deploy/README.md` §1 | 서버 준비 1, 2, 6번을 코드로 옮긴다. 3~5번은 수동 유지(결정 필요 4) |

## 3. 범위
**포함**
- `infra/`: CDK 앱(TypeScript) 하나, 스택 하나(`GraffitiStagingStack`), 리전 `ap-northeast-2`
- 보안 그룹: 80, 443 전체 공개(IPv4), 22는 `sshAllowedCidrs`로 받은 팀원 CIDR만. 그 밖의 인바운드 없음
- EC2 인스턴스 1대: Ubuntu 24.04 LTS(x86_64), `t3.medium`(4GB, D-36), 루트 볼륨 gp3 30GB 암호화, IMDSv2 필수, 기본 VPC의 퍼블릭 서브넷
- Elastic IP 1개와 인스턴스 연결
- SSH 키 쌍: 팀이 만든 **공개 키**를 설정으로 받아 EC2 키 쌍으로 등록 (개인 키는 AWS에도 저장소에도 두지 않는다)
- `sshAllowedCidrs` 검증 함수(순수 로직): 비어 있거나, 형식이 틀리거나, 너무 넓으면 synth 실패
- 스택 종료 보호(termination protection), 비용 추적용 태그(`Project=graffiti`, `Env=staging`)
- 출력: 인스턴스 ID, 공개 IP(Elastic IP), 보안 그룹 ID (비밀값 없음)
- 테스트(jest + `aws-cdk-lib/assertions`), CI의 `infra` 작업(`npm ci` → 테스트), Dependabot npm `/infra` 항목
- `infra/README.md`: bootstrap, `cdk diff`, `cdk deploy`, 삭제 절차(종료 보호 해제 순서), 팀원 IP 바뀔 때 갱신 절차
- `deploy/README.md` §1을 "인프라는 `infra/`로 만들고, Docker 설치와 배포 사용자 준비만 수동"으로 갱신, `ARCHITECTURE.md` §7에 인프라 코드 위치 한 줄

**제외 (NOT in scope)**
- 도메인, Route 53, A 레코드, 인증서 → 도메인이 정해진 뒤. 12a README §2, §6 그대로
- Secrets Manager, `.env` 값, Google OAuth → 사람이 README대로 (SEC-06)
- GitHub Actions 배포 경로 변경 → 22를 팀원 IP로 제한하면 Actions의 SSH 배포가 막힌다. 해결책(수동 배포, SSM, 자체 호스팅 러너)은 팀 결정이 필요해 `TODOS.md`에 기록 (미해결 질문 1)
- SSM Session Manager 접속, 인스턴스 프로파일(역할) → 지금은 서버가 AWS API를 부르지 않는다. 12b 백업(SEC-11)에서 S3 권한이 필요해질 때 추가 (`TODOS.md`)
- 백업 버킷, 스냅샷, CloudWatch 알람 → 12b
- 운영 스택, 운영 도메인 → 12b
- Docker 설치, `deploy` 사용자, `/opt/graffiti` 준비를 cloud-init(user data)로 자동화 → 결정 필요 4. 추천은 이번에는 수동 유지
- CDK로 `cdk bootstrap`을 코드화하지 않는다 (CLI로 1회, 사람이 승인)

## 4. 현재 상태 (검증된 사실)
- `infra/` 디렉터리 없음. IaC 코드 없음 (저장소 루트: `backend`, `frontend`, `ml`, `deploy`, `docs`)
- `deploy/README.md` §1이 EC2 사양과 보안 그룹을 문장으로 정의: Ubuntu 24.04, 4GB급, 디스크 20GB 이상, 80/443 전체, 22는 팀원 IP와 Actions, 3306/8080 닫음, Elastic IP
- `docs/SECURITY.md` SEC-10: "SSH는 팀원 IP만". README §1.2의 "처음에는 22를 열고 12b에서 좁힙니다"와 어긋남 → 이 명세는 **SEC-10을 따른다** (사람이 확정, 2026-10-28)
- `.github/dependabot.yml`에 `/infra` 항목 없음. `.github/workflows/ci.yml`에 `infra` 작업 없음 (backend, frontend, ml, secret-scan만)
- `.gitignore`에 `cdk.out/`, `infra/node_modules/` 없음
- AWS 계정(2026-10-28 읽기 전용 조회): `ap-northeast-2`에 기본 VPC 1개, 인스턴스 0, Elastic IP 0, CloudFormation 스택 0
- 같은 조회(2026-10-28)로 확인한 것: `t3.medium`은 `ap-northeast-2a`~`2d` 네 AZ 모두에서 제공된다. 기본 VPC의 기본 서브넷 4개는 AZ별로 하나씩이고 모두 공개 IP 자동 할당이 켜져 있다. Canonical(소유자 `099720109477`)의 `ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*` 패턴이 22개 이미지를 찾았고 최신은 2026-10-23 빌드(x86_64, EBS, gp3). 이미지가 1~2주마다 새로 나오므로 조회 결과를 `cdk.context.json`에 캐시하지 않으면 `cdk deploy`마다 인스턴스 교체 위험이 있다(§6)
- Node 22를 frontend와 CI가 이미 사용 중

## 5. 설계

```
사람 (로컬, AWS 자격 증명)
  └─ cd infra && npm ci
       ├─ npm test                                   ← CI도 실행 (자격 증명 불필요)
       ├─ cdk bootstrap aws://<계정>/ap-northeast-2  ← 1회, 승인 후 (CDKToolkit 스택 생성)
       ├─ cdk diff  -c sshAllowedCidrs=<팀원CIDR,...> -c sshPublicKey="ssh-ed25519 ..."
       └─ cdk deploy (diff를 사람이 확인한 뒤)
              └─ GraffitiStagingStack (termination protection)
                    ├─ SecurityGroup  in: 80,443 ← 0.0.0.0/0 / 22 ← sshAllowedCidrs만
                    ├─ KeyPair        (공개 키만)
                    ├─ Instance       t3.medium, Ubuntu 24.04, gp3 30GB 암호화, IMDSv2
                    └─ EIP + 연결     → 출력: 공개 IP (나중에 A 레코드의 값)
```

- 이 명세에는 서버 안의 큐나 상태 변경이 없다(INV 해당 없음). 배포 동시성은 CloudFormation의 스택 잠금이 맡는다
- **Search Before Building**: 아래는 AWS 문서와 AWS 스킬(`launching-ec2-instance-with-best-practices`), CDK 문서에서 확인한 내용이고, 구현 때 설치된 `aws-cdk-lib` 버전의 API 문서로 다시 확인한다
  - IMDSv2 강제: `ec2.Instance`의 `requireImdsv2` (CDK API 문서)
  - 루트 볼륨 암호화 gp3: `blockDevices` + `ec2.BlockDeviceVolume.ebs(..., { encrypted: true, volumeType: GP3 })`
  - 최소 포트 보안 그룹, 암호화 EBS, 태깅: AWS 스킬의 권장값
  - 동적 값(팀원 IP)은 코드에 넣지 않고 CDK context(`-c`)로 받는다. 조회 결과(AMI, VPC)는 `cdk.context.json`에 캐시해 커밋한다(CDK 권장)
- 추가 파일 수는 앱 1, 스택 1, 검증 함수 1이다. 더 줄이려면 스택에 검증을 합칠 수 있지만, 순수 함수로 두어야 AWS 없이 거부 테스트를 쓸 수 있다(§8)

| 파일 (예정) | 역할 |
|---|---|
| `infra/bin/graffiti.ts` | 앱 진입점. `env.account`는 `CDK_DEFAULT_ACCOUNT`에서 읽고 계정 ID를 코드에 쓰지 않는다 |
| `infra/lib/graffiti-staging-stack.ts` | 보안 그룹, 키 쌍, 인스턴스, EIP, 출력, 태그, 종료 보호 |
| `infra/lib/ssh-cidrs.ts` | `parseSshCidrs(value)`: 순수 함수. 빈 값, 형식 오류, `/0`, `::/0`, 너무 넓은 범위를 거부 |
| `infra/test/ssh-cidrs.test.ts` | 검증 함수 단위 테스트 |
| `infra/test/graffiti-staging-stack.test.ts` | 합성된 템플릿에 대한 단언 (§7) |
| `infra/package.json`, `package-lock.json`, `cdk.json`, `tsconfig.json`, `jest.config.*` | 의존성은 정확한 버전으로 고정 (SEC-16) |
| `infra/cdk.context.json` | 조회 캐시 (AMI, VPC). 비밀값 아님 |
| `infra/README.md` | 운영 절차 |
| `.github/workflows/ci.yml` | `infra` 작업 추가 (npm ci, 테스트. `cdk synth`는 자격 증명이 없어 돌리지 않음) |
| `.github/dependabot.yml`, `.gitignore` | `/infra` npm 항목, `infra/cdk.out/`, `infra/node_modules/` |

- 앱 설정값(`GraffitiProperties`) 추가 없음(INV-10). 인프라 수치(포트, 인스턴스 크기, 볼륨 크기)는 `infra/lib/` 한 곳에 상수로 두고 `ARCHITECTURE.md` §7에 표로 적는다
- **결정 필요 1 — IaC 위치와 언어**: **추천: 루트 `infra/`, TypeScript** (Node 22가 이미 CI에 있고, CDK 문서와 예제가 TypeScript 중심). 대안: Python (ml이 Python이지만 CDK 쪽 의존성이 하나 더 늘어남)
- **결정 필요 2 — 인스턴스와 볼륨**: **추천: `t3.medium`(x86_64), gp3 30GB 암호화**. 이유: 4GB(D-36), 12a 이미지가 amd64로 빌드되므로 ARM(`t4g`)은 이미지와 ML 의존성 호환 검증이 필요. 30GB는 README 하한 20GB에 07b 모델 가중치 여유를 더한 값. 월 비용(2026-10-28 Price List API, 서울, 온디맨드, 730시간, 24시간 가동): `t3.medium` 약 $44.35 = 인스턴스 $37.96 + gp3 30GB $2.74 + 공개 IPv4 $3.65. 비교로 `t3a.medium` $40.55, `t4g.medium` $36.75. 데이터 전송, 세금, 스냅샷(12b)은 제외. 인스턴스를 중지해도 볼륨과 Elastic IP 요금은 계속 나온다(D-04)
- **결정 필요 3 — 네트워크**: **추천: 기본 VPC의 퍼블릭 서브넷을 조회해 사용** (새 VPC, NAT 없음, D-04). 대안: 전용 VPC (격리는 좋지만 비용과 코드가 늘고 서버 1대에는 과함)
- **결정 필요 4 — 서버 부트스트랩 범위**: **추천: 인프라만 만들고 Docker 설치, `deploy` 사용자, `/opt/graffiti`는 README §1.3~5 수동 유지.** 이유: 서버 안 설정은 실제 서버가 있어야 검증되고 한 번뿐인 작업이라 코드화 이득이 작다. 대안: cloud-init으로 자동화(재현성은 좋지만 실패해도 CloudFormation은 성공으로 끝나 조용히 틀릴 수 있음)
- **결정 필요 5 — 허용 CIDR의 최소 범위**: **추천: IPv4 접두사 길이 24 이상만 허용** (`/32` 개인 IP, `/24` 연구실 대역 수준까지). 이유: `/0` 같은 전체 개방을 막으면서 학교 NAT 대역은 허용. 이 수치는 인프라 상수이고 근거는 SEC-10

## 6. 실패 모드

| 경로 | 실패 상황 | 처리 (사용자에게 보이는 결과) | 테스트 |
|---|---|---|---|
| synth | `sshAllowedCidrs`를 안 줌 또는 빈 값 | synth 실패, 필요한 값이 무엇인지 알려 주는 오류 (조용히 22를 닫은 채 배포되지 않음) | `ssh-cidrs` 단위 테스트, 스택 테스트(미지정 시 예외) |
| synth | `0.0.0.0/0`, `::/0`, 접두사 24 미만, 형식 오류 | synth 실패, 어떤 값이 왜 거부됐는지 오류 | 단위 테스트 (SEC-10 거부 테스트) |
| 배포 | 팀원 IP가 바뀌어 SSH가 막힘 | `-c sshAllowedCidrs`를 고쳐 `cdk deploy`. 보안 그룹 규칙만 바뀌고 인스턴스는 그대로여야 함 | QA: 규칙 변경 후 `cdk diff`에 인스턴스 교체 없음 |
| 배포 | AMI 조회가 바뀌어 인스턴스 교체(Elastic IP 유지, 디스크와 DB 볼륨 유실) | AMI를 context에 캐시해 커밋하므로 새 AMI가 나와도 `cdk diff`에는 변화 없음. 의도적 교체는 context를 갱신하며 diff로 확인 | QA: 캐시 커밋 후 `cdk diff` 변화 없음 |
| 배포 | 가용 영역에 `t3.medium` 용량 없음 | 배포가 `InsufficientInstanceCapacity`로 실패하고 롤백(조용하지 않음). `infra/README.md`에 다른 AZ 지정 방법 기록 | 문서 점검 |
| 배포 | 배포가 중간에 실패 | CloudFormation 자동 롤백, 남은 리소스 없음. 롤백 실패 시 `cdk rollback` 절차를 README에 기록 | 문서 점검 |
| 삭제 | 스택이 실수로 삭제됨 | 종료 보호로 삭제 거부 | 스택 테스트(`terminationProtection === true`) |
| 계정 | 다른 AWS 계정이나 리전에 배포 | `cdk deploy` 전에 `sts get-caller-identity` 확인을 README 절차에 넣고, 리전은 코드에 고정 | 문서 점검 + QA |
| 비밀값 | 공개 키 자리에 개인 키를 붙여넣음 | 입력이 `ssh-` 또는 `ecdsa-`로 시작하는 공개 키 형식인지 검사하고 `PRIVATE KEY`가 들어 있으면 거부 | 단위 테스트 |
| 비용 | 인스턴스를 켠 채로 방치 | 태그와 출력으로 식별 가능. 비용 알람은 12b | 해당 없음 (TODOS) |

critical gap: 0 (Actions 배포 경로는 "조용한 실패"가 아니다. `STAGING_HOST`를 넣으면 배포 단계가 SSH 연결 실패로 눈에 보이게 실패한다. 해결은 미해결 질문 1)

## 7. 수용 기준 (테스트 가능하게)
- [ ] AC-1: Given 유효한 `sshAllowedCidrs`와 공개 키 When 스택을 합성하면 Then `AWS::EC2::Instance` 1개, `AWS::EC2::SecurityGroup` 1개, `AWS::EC2::EIP` 1개와 연결 리소스가 있고 NAT 게이트웨이, 로드밸런서, 새 VPC는 없다
- [ ] AC-2 (SEC-10): Given 위 스택 When 보안 그룹 인바운드를 보면 Then 80, 443은 `0.0.0.0/0`이고, 22는 입력한 CIDR들만이며, 그 밖의 포트(3306, 8080 포함)와 `0.0.0.0/0`의 22는 없다
- [ ] AC-3 (SEC-10 거부): Given `sshAllowedCidrs`가 없음, 빈 값, `0.0.0.0/0`, `::/0`, `10.0.0.0/8`, `abc` When 합성 또는 검증하면 Then 실패한다
- [ ] AC-4: Given 인스턴스 When 메타데이터 옵션을 보면 Then IMDSv2가 필수(`HttpTokens: required`)
- [ ] AC-5: Given 루트 볼륨 When 속성을 보면 Then gp3, 암호화, 크기 30GB (README 하한 20GB 이상)
- [ ] AC-6 (D-36): Given 인스턴스 When 타입을 보면 Then 메모리 4GB 이상(`t3.medium`), AMI는 Ubuntu 24.04 LTS x86_64
- [ ] AC-7: Given 스택 When 속성을 보면 Then 종료 보호가 켜져 있고 `Project`, `Env` 태그가 리소스에 붙는다
- [ ] AC-8 (SEC-06): Given 합성된 템플릿과 `infra/` 소스 When 검사하면 Then 개인 키, 팀원 IP, 계정 ID가 없다 (gitleaks CI 통과, 계정 ID는 `CDK_DEFAULT_ACCOUNT`에서만)
- [ ] AC-9 (SEC-16): Given `infra/package.json` When 보면 Then 의존성이 정확한 버전으로 고정돼 있고 Dependabot에 `/infra`가 있다
- [ ] AC-10: Given PR When CI가 돌면 Then `infra` 작업이 `npm ci`와 테스트를 실행하고 실패하면 PR이 막힌다
- [ ] AC-11 (QA, 실제 계정): Given `cdk deploy` 완료 When 팀원 IP에서 22에 접속하면 Then 성공하고, 목록에 없는 IP(예: 휴대폰 핫스팟)에서는 22가 타임아웃되고 3306, 8080도 타임아웃된다

## 8. 테스트 계획
| 층 | 대상 | 파일 (예정) |
|---|---|---|
| 단위 | `parseSshCidrs`: 유효 목록 통과, 빈 값, `/0`, `::/0`, 접두사 24 미만, 형식 오류, 공백, 중복 처리. 공개 키 형식 검사(개인 키 거부) | `infra/test/ssh-cidrs.test.ts` |
| 템플릿 단언 | AC-1 ~ AC-7: `Template.hasResourceProperties`로 인바운드 규칙, `HttpTokens`, 볼륨, 인스턴스 타입, 종료 보호, 태그. **보안 그룹은 규칙 전체를 비교해서 의도하지 않은 포트가 추가되면 실패**하게 한다 | `infra/test/graffiti-staging-stack.test.ts` |
| CI | `infra` 작업(`npm ci` → `npm test`) | `.github/workflows/ci.yml` |
| QA 시나리오 | `cdk diff`가 생성만 보여 주는지, 배포 후 AWS MCP로 보안 그룹과 인스턴스 속성을 읽어 AC-2, 4, 5 재확인, 다른 네트워크에서 포트 접속 확인(AC-11), `cdk destroy` 절차(종료 보호 해제 → 삭제 → 리소스 0개 확인) | 수동 체크리스트 (`infra/README.md`) |
| 미검증으로 남김 | 24시간 이상 방치 시 비용, 다른 AZ 용량 오류 | — |

- 테스트는 `env`를 지정하고 컨텍스트 조회는 더미 값을 쓰는 방식으로 자격 증명 없이 돌린다. 이 동작은 구현 때 설치된 CDK 버전에서 확인한다
- 회귀를 잡지 못하는 테스트는 만들지 않는다: 단언은 상수 복사가 아니라 "허용되지 않은 포트가 없다", "`0.0.0.0/0`의 22가 없다" 같은 부정 조건을 포함한다. 단언을 일부러 깨 보는 변형(22를 `0.0.0.0/0`으로 바꿈)에서 테스트가 실패하는지 구현 중 한 번 확인한다

## 9. 구현 에이전트 규칙 (이 명세 전용)
- 이 명세의 범위만 구현한다. 범위 밖이 필요해 보이면 멈추고 질문(NEEDS_CONTEXT)
- **`cdk bootstrap`과 `cdk deploy`는 사람의 승인 없이 실행하지 않는다.** 항상 `cdk diff` 결과를 먼저 보여 준다. 두 명령 모두 계정에 비용 또는 리소스를 만든다
- `--hotswap`, `--express`는 쓰지 않는다 (운영으로 이어질 스택이다)
- 22번 포트에 `0.0.0.0/0`, `::/0`을 쓰지 않는다. 테스트 편의를 위한 완화도 금지 (Gate Integrity)
- 팀원 IP, 개인 키, 계정 ID를 소스, 테스트, 문서, 커밋 메시지에 쓰지 않는다. 테스트에는 문서용 예약 대역(`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`)만 쓴다
- `.env`를 읽거나 쓰지 않는다. 비밀값이 필요해 보이면 멈춘다 (SEC-06)
- `deploy.yml`, `deploy/compose.yml`, nginx 설정은 고치지 않는다 (12a 범위). `deploy/README.md` §1과 `ARCHITECTURE.md` §7만 같은 커밋에서 갱신한다
- CDK와 npm 의존성은 정확한 버전으로 고정하고(`^`, `~` 금지), 새 의존성은 `aws-cdk-lib`, `constructs`, 테스트와 TypeScript 도구 외에는 추가하지 않는다 (SEC-16)
- AWS 조회와 변경은 AWS MCP 서버를 쓴다. 리소스는 CDK로만 만들고 콘솔이나 CLI로 따로 만들지 않는다 (드리프트 방지)

## 10. 롤백
- 코드 롤백: 이 PR을 되돌려도 이미 배포한 AWS 리소스는 남는다
- 리소스 삭제: 종료 보호를 끄고 `cdk destroy`. 인스턴스, 볼륨, Elastic IP가 삭제된다. 스테이징 데이터는 버려도 된다(12a §10). 도메인을 이미 연결했다면 A 레코드를 먼저 지운다
- DB 마이그레이션 없음

---

## 미해결 질문
1. **Actions 배포 경로**: SEC-10에 따라 22를 팀원 IP로만 열면, IP가 고정되지 않은 GitHub Actions의 `deploy.yml` SSH 단계는 서버에 닿지 못한다. 후보: (a) 12b까지 서버에서 수동 `compose pull && up -d`, (b) SSM으로 배포(12a 결정 변경 필요), (c) 자체 호스팅 러너. **추천: (a)** — 스테이징은 12b 전까지 수동으로 두고, `STAGING_HOST`는 배포 경로가 정해질 때까지 넣지 않는다. `deploy/README.md` §4의 "넣는 순간부터 배포됩니다" 안내와 §1.2를 같이 고쳐야 한다. 이 명세 범위 밖이라 `TODOS.md`에 기록했다
2. 월 예상 비용이 D-04 허용 범위인지 승인자가 확인. 조회 결과: `t3.medium` 24시간 가동 시 월 약 $44.35(연 약 $532.20). 비용을 더 줄이려면 `t4g.medium`(월 약 $36.75)이 있으나 ARM이라 이미지와 ML 의존성의 arm64 빌드 검증이 필요해서 **추천은 `t3.medium` 유지**. 계정의 프리 티어 적용 여부는 확인하지 못했다(미검증). 예산 알림(AWS Budgets)은 12b에서 정한다

---

## 리뷰 기록 (/gf-review가 추가)
<!-- 날짜 · 커밋 SHA · CRITICAL n / INFO n · AUTO-FIX n · ASK 결과 · 상태 -->

## QA 기록 (/gf-qa가 추가)
<!-- 날짜 · 커밋 SHA · 시나리오 통과/실패 · 발견 버그(ISSUE-NNN) · 상태 -->

## 보안 감사 기록 (/gf-security가 추가, 해당 시)

## 배포 기록 (/gf-deploy-check가 추가)
