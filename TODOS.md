# TODOS — MVP 범위 밖으로 미룬 것

> MVP에서 **만들지 않는** 기능과 개선 아이디어입니다. 구현 중에 "있으면 좋겠다"는 생각이 들면 코드에 넣지 말고 여기에 추가합니다.
> 형식: **What / Why / Context / Depends on**

## 기능

### 아카이브 열람
- **What**: 지난 페이지 목록(번호, 기간, 최종 점유율, 넘어간 사유, 미니맵 썸네일)과 읽기 전용 열람 화면
- **Why**: 원래 기획의 핵심 재미 요소였으나 MVP에서 제외됨 (D-17)
- **Context**: 데이터는 이미 `page.status=ENDED`로 보관 중. `page.final_occupancy_bitmap`도 저장 중이라 썸네일은 바로 쓸 수 있음. 필요한 것은 목록, 조회 API와 읽기 전용 캔버스 모드
- **Depends on**: 03-canvas-read (청크 조회 재사용)

### 사용자 신고
- **What**: doodle 신고 → 관리자 화면에 우선순위로 노출
- **Why**: 여러 사람이 조금씩 그려서 완성하는 "협동 도배"는 작성자 단위 AI 검사로 못 잡음 (D-20)

### AI 적발 기반 자동 차단
- **What**: 같은 사용자가 24시간 안에 N회 거부되면 자동으로 그리기 제한
- **Why**: 관리자 부담 감소. 오탐 연쇄 위험 때문에 MVP 제외 (D-20)

### 잉크 시스템
- **What**: 사용자별 잉크 양 제한
- **Why**: 도배 방지. 동시성과 서버 부하 문제로 연기됨. 현재는 30분 쿨다운(D-12)으로 대체

### 텍스트 수정, 게시된 doodle 부분 지우기
- **Why**: 수정하면 모더레이션 재검사, 점유율 재계산, 버전 관리가 필요해서 제외 (D-07, D-11)

### 누적 접속자 수, 작성자 표시
- 팀 결정으로 제외 (D-22)

### 늦게 들어온 사람에게 진행 중 초안 스냅샷 전송
- **What**: 초안을 구독한 시점에 그때까지의 점 전체를 받기
- **Context**: 현재는 증분만 중계 (D-29). 클라이언트끼리 요청/응답(`draft:snapshot-request`)으로 서버 저장 없이 구현 가능

## 인프라, 운영

### 다중 서버 확장
- **What**: SimpleBroker → 외부 STOMP 브로커(RabbitMQ 등), `PageCommandProcessor` → 분산 구현
- **Depends on**: 동시 접속 300명을 넘는 수요 (D-01)

### 자체 ML 모델 미세조정
- **What**: 그림자 모드에서 쌓인 실제 낙서 + 관리자 판정으로 이미지 분류 모델 미세조정, 새 버전을 `models.lock`에 등록
- **Why**: CLIP 제로샷은 출시용 출발점일 뿐 (D-37)
- **Context**: 학습 데이터 추출은 작성자 정보 없이 (D-38, SEC-17). 새 버전은 반드시 팀 평가 세트와 운영 데이터로 이전 버전과 비교
- **Depends on**: 07b 배포 + 운영 표본 (D-39: 정상 500, 부적절 30 이상)

### ML 모드 ENFORCE 전환
- **What**: `graffiti.moderation.ml.mode`를 `SHADOW` → `ENFORCE`
- **Why**: 자체 모델을 실제 차단에 사용 (D-34)
- **Context**: D-39 기준 전부 충족 + 평가 보고서 + **팀 승인**. 전환 후 오탐 복구 추이 감시, 기준 초과 시 SHADOW로 복귀
- **Depends on**: 미세조정(선택), 07b

### 손글씨 검사를 비전 LLM으로 교체
- **What**: OCR 정확도가 부족하면 손글씨 판정을 비전 LLM 제공자로 교체
- **Context**: 유료라 D-04와 충돌. `ModerationProvider` 구현체 교체로 가능 (D-35)

### ML 서버 분리
- **What**: ENFORCE 이후 부하가 크면 ML 서버를 전용 인스턴스로 분리 (D-36 재검토 시점)

### 학습 데이터 선택 동의로 전환 (조건부)
- **What**: 학교 확인에서 지적이 나오면 학습 목적을 필수 동의에서 분리 (D-38 알려진 위험)

### 모더레이션 제공자 고도화
- **What**: 손글씨 한국어 욕설 대응 (범용 비전 LLM 등)
- **Context**: `ModerationProvider` 인터페이스로 교체 가능 (D-19)

### OpenAI 검사와 DoodleRenderer (07 나머지, 공개 전 게이트)
- **What**: OpenAI omni-moderation(텍스트 `openai-text`, 이미지 `openai-image`)과 그림 렌더링(`DoodleRenderer`)을 만들어 `text-checks`와 `drawing-checks`의 스텁을 대체
- **Why**: SEC-05는 텍스트를 금칙어 + ChatKHU + OpenAI 이중 검사로 정한다. `07-moderation-text`는 ChatKHU까지만 연결하므로 이것 없이는 SEC-05가 완성되지 않고, 스텁이 남아 있는 동안 모든 텍스트가 보류(`HELD`)된다
- **Context**: OpenAI 키(`OPENAI_API_KEY`)를 아직 확인하지 않았다. OpenAI 카테고리를 `FILTER.md`의 4개에 어떻게 대응시킬지 정해야 한다
- **Depends on**: 07-moderation-text, `FILTER.md` 확정

### 보류된 욕설을 관리자가 게시할 때 자동 축소
- **What**: `AdminReviewService.publish`가 `moderation_detail`에 `SHRINK`가 있는 doodle을 게시할 때 글자 크기를 줄이고 bbox, 청크, 점유율을 다시 계산
- **Why**: 지금은 관리자 게시가 저장된 payload를 그대로 올려서 보류된 욕설이 원래 크기로 나온다(`07-moderation-text` 결정 필요 2의 B)
- **Context**: `PublishWithheld` 큐 명령이 행의 payload, bbox, 청크를 다시 써야 한다. 관리자의 크기 되돌리기와 함께 설계
- **Depends on**: 07-moderation-text, 10 나머지

### ChatKHU 크레딧 잔액 감시
- **What**: `GET /v1/gateway/credits/`로 잔액을 읽어 기준 아래로 떨어지면 알린다
- **Why**: 월 배정이 약 $4로 추정되고(1 크레딧 ≈ $0.001) 소진되면 모든 텍스트가 보류 큐로 몰린다
- **Context**: 소진 시 응답 코드는 확인하지 못했다. 12b의 경보와 함께 하는 것이 자연스럽다
- **Depends on**: 07-moderation-text, 12b

### 수익 구조
- 설계 범위 밖 (D-04)
