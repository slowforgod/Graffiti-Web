# DB 설계 (MySQL 8)

> 스키마를 바꾸면 이 문서와 마이그레이션 파일을 **같은 커밋에서** 수정합니다.
> 마이그레이션 도구(Flyway 권장)는 `00-bootstrap`에서 확정합니다. 아래 DDL은 첫 마이그레이션의 기준입니다.

## 1. ERD

```
┌──────────────┐ 1     N ┌──────────────────┐ N     1 ┌──────────────┐
│ users        │────────▶│ doodle           │◀────────│ page         │
│──────────────│ author  │──────────────────│ page    │──────────────│
│ id PK        │         │ id PK            │         │ id PK        │
│ google_sub UQ│         │ page_id FK       │         │ seq UQ       │
│ email UQ     │         │ author_id FK     │         │ status       │
│ nickname     │         │ kind             │         │ live_flag UQ │ ← INV-11
│ cursor_color │         │ status           │         │ 설정 스냅샷   │
│ role         │         │ moderation_state │         │ 시각들        │
│ banned_at    │         │ bbox_x/y/w/h     │         │ 최종 비트맵   │
│ last_posted_at│        │ payload JSON     │         └──────────────┘
│ consented_at │         └──────────────────┘
└──────────────┘                  │ 1
       │ 1                        │ N
       │                 ┌──────────────────┐
       │                 │ doodle_chunk     │  PK(page_id, chunk_index, doodle_id)
       │                 └──────────────────┘
       │ N
┌────────────────────┐          ┌──────────────────┐
│ moderation_action  │          │ ml_prediction    │  doodle별 자체 ML 판정 기록 (D-40)
└────────────────────┘          │ doodle_id FK     │  그림자 모드 평가 (D-39)
 AI/관리자/시스템 조치 감사 기록   └──────────────────┘
 (SEC-14)
(+ Spring Session JDBC 테이블: SPRING_SESSION, SPRING_SESSION_ATTRIBUTES)
```

## 2. DDL

```sql
-- 문자셋: utf8mb4 / 시간: 전부 UTC로 저장, DATETIME(3)

CREATE TABLE users (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  google_sub            VARCHAR(64)  NOT NULL,              -- Google 계정 고유 ID (이메일보다 불변)
  email                 VARCHAR(255) NOT NULL,              -- 관리자만 조회 가능 (D-22)
  nickname              VARCHAR(40)  NOT NULL,              -- "익명의 펭귄 #3842"
  cursor_color          CHAR(7)      NOT NULL,              -- '#RRGGBB'
  role                  ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
  privacy_consented_at  DATETIME(3)  NULL,                  -- NULL이면 동의 전 (SEC-13)
  banned_at             DATETIME(3)  NULL,                  -- NULL이면 정상
  ban_reason            VARCHAR(500) NULL,
  last_posted_at        DATETIME(3)  NULL,                  -- 쿨다운 기준 (INV-08)
  created_at            DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_google_sub (google_sub),
  UNIQUE KEY uk_users_email (email),
  UNIQUE KEY uk_users_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE page (
  id                        BIGINT     NOT NULL AUTO_INCREMENT,
  seq                       INT        NOT NULL,             -- 제 N장
  status                    ENUM('ACTIVE','COUNTDOWN','ENDED') NOT NULL,
  -- 종료되지 않은 페이지는 최대 1개 (INV-11): ENDED면 NULL → 유니크 제약에서 제외
  live_flag                 TINYINT AS (CASE WHEN status <> 'ENDED' THEN 1 ELSE NULL END) STORED,
  -- 생성 시점 설정 스냅샷 (D-06, INV-10)
  width                     INT        NOT NULL,
  height                    INT        NOT NULL,
  chunk_size                INT        NOT NULL,
  cell_size                 INT        NOT NULL,
  countdown_threshold_permille SMALLINT NOT NULL,           -- 700 = 70%
  countdown_duration_sec    INT        NOT NULL,             -- 21600
  max_lifetime_sec          INT        NOT NULL,             -- 604800
  -- 시각
  created_at                DATETIME(3) NOT NULL,
  countdown_started_at      DATETIME(3) NULL,                -- 한 번 기록되면 바뀌지 않음 (INV-03)
  scheduled_end_at          DATETIME(3) NOT NULL,            -- min(created+7d, countdown+6h)
  ended_at                  DATETIME(3) NULL,
  end_reason                ENUM('OCCUPANCY','MAX_LIFETIME') NULL,
  final_occupancy_permille  SMALLINT   NULL,
  final_occupancy_bitmap    VARBINARY(4096) NULL,            -- 160×90 비트 = 1,800바이트 (D-08, 아카이브용)
  PRIMARY KEY (id),
  UNIQUE KEY uk_page_seq (seq),
  UNIQUE KEY uk_page_live (live_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE doodle (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  page_id           BIGINT      NOT NULL,
  author_id         BIGINT      NOT NULL,
  kind              ENUM('DRAWING','TEXT') NOT NULL,
  status            ENUM('PUBLISHED','REJECTED','HELD','HIDDEN','DELETED') NOT NULL,   -- HELD: 관리자 검토 대기 (V5)
  moderation_state  ENUM('PASSED','REJECTED','HELD','UNCHECKED') NOT NULL,           -- AI 판정. 관리자가 게시해도 그대로
  moderation_detail JSON        NULL,                        -- 검사별 결과 (아래 3절). 원문 글자 없음 (SEC-05)
  bbox_x            INT         NOT NULL,                    -- 월드 좌표, 서버가 계산/검증
  bbox_y            INT         NOT NULL,
  bbox_w            INT         NOT NULL,                    -- ≤ 1024 (D-11)
  bbox_h            INT         NOT NULL,
  payload           JSON        NOT NULL,                    -- 아래 3절 형식
  payload_bytes     INT         NOT NULL,                    -- ≤ 2MB (D-11)
  submitted_at      DATETIME(3) NOT NULL,                    -- ✓ 누른 시각
  published_at      DATETIME(3) NULL,
  removed_at        DATETIME(3) NULL,                        -- HIDDEN/DELETED 된 시각
  removed_by        BIGINT      NULL,                        -- 작성자 본인 또는 관리자
  PRIMARY KEY (id),
  KEY idx_doodle_page_status (page_id, status),
  KEY idx_doodle_author (author_id, submitted_at),
  KEY idx_doodle_moderation (moderation_state, submitted_at),
  CONSTRAINT fk_doodle_page   FOREIGN KEY (page_id)   REFERENCES page(id),
  CONSTRAINT fk_doodle_author FOREIGN KEY (author_id) REFERENCES users(id),
  CONSTRAINT ck_doodle_bbox CHECK (bbox_w BETWEEN 1 AND 1024 AND bbox_h BETWEEN 1 AND 1024)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- doodle의 bbox가 걸치는 청크마다 1행 (최대 4×4=16행, 보통 1~4행) (D-24)
-- REJECTED·HELD로 저장된 doodle도 행을 만든다(관리자가 게시할 때 그대로 씀). 청크 조회는 PUBLISHED만
CREATE TABLE doodle_chunk (
  page_id      BIGINT   NOT NULL,
  chunk_index  SMALLINT NOT NULL,                            -- cy * 16 + cx, 0~143
  doodle_id    BIGINT   NOT NULL,
  PRIMARY KEY (page_id, chunk_index, doodle_id),
  KEY idx_doodle_chunk_doodle (doodle_id),
  CONSTRAINT fk_dc_doodle FOREIGN KEY (doodle_id) REFERENCES doodle(id)
) ENGINE=InnoDB;

CREATE TABLE moderation_action (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  actor_type       ENUM('AI','ADMIN','SYSTEM') NOT NULL,
  actor_id         BIGINT      NULL,                         -- 관리자 user id
  action           ENUM('REJECT','HOLD','APPROVE','MARK_UNCHECKED','DELETE','HIDE','RESTORE','BAN','UNBAN') NOT NULL,
                   -- HOLD: AI가 보류, APPROVE: 관리자가 보류를 게시, RESTORE: 관리자가 거부를 게시 (V5)
  target_doodle_id BIGINT      NULL,
  target_user_id   BIGINT      NULL,
  reason           VARCHAR(500) NULL,
  detail           JSON        NULL,
  created_at       DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ma_target_user (target_user_id, action),
  KEY idx_ma_target_doodle (target_doodle_id),
  KEY idx_ma_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 자체 ML 모델 판정 기록 (D-39 평가 지표 계산용, D-40)
-- 게시 트랜잭션과 분리해서 기록. 실패해도 게시 결과에 영향 없음 (INV-13)
CREATE TABLE ml_prediction (                                -- V6, 07b. 그림 doodle의 ML 분석 1회 = 1행
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  doodle_id          BIGINT       NOT NULL,                  -- 작성자 열은 없음 (SEC-17)
  model_version      VARCHAR(64)  NULL,                      -- models.lock의 버전 이름 (outcome=OK일 때)
  mode               ENUM('SHADOW','ENFORCE') NOT NULL,       -- 분석 당시 모드
  outcome            ENUM('OK','TIMEOUT','ERROR') NOT NULL,   -- ML 서버 호출 결과 (렌더링 실패도 ERROR)
  image_label        ENUM('SAFE','FLAGGED') NULL,             -- 이미지 분류 판정 (outcome=OK일 때)
  image_scores       JSON         NULL,                       -- 카테고리별 점수 {"SEXUAL":0.81,"HATE":0.04}
  ocr_text           VARCHAR(500) NULL,                       -- 손글씨에서 읽은 글자, 500자에서 자름 (로그 출력 금지, SEC-17)
  ocr_text_outcome   ENUM('NONE','PASS','REVIEW','BLOCK','ERROR','SKIPPED') NULL,  -- OCR 글자의 텍스트 검사 결과. NONE = 읽은 글자 없음
  ocr_text_category  VARCHAR(40)  NULL,                       -- BLOCK, REVIEW일 때 FILTER 카테고리
  ocr_confidence     DECIMAL(4,3) NULL,                       -- ML 서버가 돌려준 평균 신뢰도
  latency_ms         INT          NULL,                       -- ML 서버가 잰 처리 시간
  created_at         DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mlp_doodle (doodle_id),
  KEY idx_mlp_model_created (model_version, created_at),
  CONSTRAINT fk_mlp_doodle FOREIGN KEY (doodle_id) REFERENCES doodle(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Spring Session JDBC 테이블(SPRING_SESSION, SPRING_SESSION_ATTRIBUTES)은 spring-session-jdbc 3.5.x의
-- schema-mysql.sql을 그대로 V2__spring_session.sql에 넣음. 자동 생성은 끔(spring.session.jdbc.initialize-schema=never).
-- 로그인 후 세션 속성은 SecurityContext(SessionUser: userId만)이고 이메일, 토큰은 없음(SEC-13).
```

- `doodle_chunk`는 doodle을 **저장할 때** 함께 넣습니다(PUBLISHED, REJECTED, HELD 모두). 관리자가 보류·거부를 게시하면 이 행을 그대로 써서 청크에 알립니다. HIDDEN이나 DELETED가 돼도 행은 남기며, 조회할 때 `doodle.status='PUBLISHED'`로 거릅니다.
- 행은 삭제하지 않습니다(INV-07).

## 3. payload JSON 형식

좌표는 모두 **월드 좌표 정수**입니다. 색은 팔레트 인덱스(0~11), 굵기와 글자 크기는 허용 목록 안의 값만 씁니다.

```jsonc
// kind = DRAWING
{
  "v": 1,
  "strokes": [
    { "c": 3, "w": 8, "p": [1200, 340, 1203, 342, 1207, 345] }  // p = [x0,y0,x1,y1,...]
  ]
}

// kind = TEXT
{
  "v": 1,
  "text": "안녕 경희대!\n두 번째 줄",   // ≤ 50자 (줄바꿈 제외)
  "c": 0,
  "size": 24,
  "x": 1500, "y": 900                  // 왼쪽 위 기준
}
```

- 서버는 그림의 bbox를 **점에서 직접 계산**합니다(굵기의 절반만큼 확장). 클라이언트가 보낸 bbox는 쓰지 않아요.
- 텍스트 bbox는 클라이언트 값을 상한 검증해서 씁니다(D-31).

`moderation_detail` (07 틀. 검사기 이름과 결과 코드만 담고, 원문 글자·AI 응답 원문은 넣지 않음, SEC-05)

```jsonc
{
  "checks": [                                   // 설정 순서대로, 거부하는 BLOCK에서 멈춤(욕설 BLOCK은 계속, D-43)
    { "check": "personal-info", "outcome": "PASS" },  // BLOCK이면 category PERSONAL_INFO, note PHONE·EMAIL·STUDENT_ID·RRN
    { "check": "word-filter", "outcome": "BLOCK", "category": "PROFANITY" },
    { "check": "chatkhu", "outcome": "ERROR", "note": "TIMEOUT" },     // note: TIMEOUT, EXCEPTION, BAD_RESULT, HTTP_<코드>, BAD_RESPONSE, IO, RENDER
    { "check": "openai-text", "outcome": "SKIPPED" }                  // SKIPPED = 아직 구현 안 된 검사(스텁)
  ],
  "ml": { "check": "ml", "outcome": "BLOCK", "category": "SEXUAL", "mode": "ENFORCE" },  // ENFORCE의 그림만. SHADOW 결과는 ml_prediction에만
  "heldAsUnchecked": true,                      // 실제 검사가 없어 unchecked=HOLD 설정으로 보류된 경우만
  "shrunk": { "fromSize": 24 }                  // 욕설 텍스트를 shrink-font-size로 줄여 게시한 경우만 (원래 크기, D-43)
}
```

## 4. 주요 쿼리

```sql
-- 청크 조회 (화면 로딩): 인덱스 PK(page_id, chunk_index, doodle_id) 사용
SELECT DISTINCT d.id, d.kind, d.bbox_x, d.bbox_y, d.bbox_w, d.bbox_h, d.payload
FROM doodle_chunk dc JOIN doodle d ON d.id = dc.doodle_id
WHERE dc.page_id = ? AND dc.chunk_index IN (?, ?, ...) AND d.status = 'PUBLISHED';

-- 재시작 복구 (점유율 재계산)
SELECT kind, payload, bbox_x, bbox_y, bbox_w, bbox_h FROM doodle
WHERE page_id = ? AND status = 'PUBLISHED';

-- 게시 예약 (06, INV-08, 큐 안): 읽은 last_posted_at 그대로이고 차단되지 않았을 때만. <=>는 NULL끼리도 같음
UPDATE users SET last_posted_at = :now
WHERE id = :id AND banned_at IS NULL AND last_posted_at <=> :readValue;

-- 게시 되돌림 (D-28): 예약한 값이 그대로일 때만 이전 값으로
UPDATE users SET last_posted_at = :previous WHERE id = :id AND last_posted_at = :reservedAt;

-- 관리자가 보류·거부를 게시 (07 틀, 큐 안): 그 상태 그대로이고 현재 페이지일 때만. 0행이면 STATE_CONFLICT
UPDATE doodle SET status = 'PUBLISHED', published_at = :now
WHERE id = :id AND page_id = :currentPage AND status = :from;   -- :from = 'HELD' 또는 'REJECTED'

-- 관리자가 보류를 거부
UPDATE doodle SET status = 'REJECTED' WHERE id = :id AND status = 'HELD';

-- 내 doodle 목록 (08): 게시된 것만
SELECT id, bbox_x, bbox_y, bbox_w, bbox_h FROM doodle
WHERE author_id = :me AND page_id = :pageId AND status = 'PUBLISHED' ORDER BY id;

-- 내 doodle 삭제 (08, 큐 안, 한 트랜잭션에서 doodle마다): 0행이면 STATE_CONFLICT로 전부 롤백
UPDATE doodle SET status = 'DELETED', removed_at = :now, removed_by = :me
WHERE id = :id AND page_id = :currentPage AND author_id = :me AND status = 'PUBLISHED';

-- 상태 전이 (INV-03): 영향받은 행이 0이면 예외
UPDATE page SET status = 'COUNTDOWN', countdown_started_at = ?, scheduled_end_at = ?
WHERE id = ? AND status = 'ACTIVE';

-- 관리자: AI 적발 횟수 순 사용자
SELECT u.id, u.nickname, u.email, COUNT(*) AS rejects
FROM moderation_action ma JOIN users u ON u.id = ma.target_user_id
WHERE ma.action = 'REJECT' AND ma.actor_type = 'AI'
GROUP BY u.id ORDER BY rejects DESC LIMIT ? OFFSET ?;

-- 그림자 모드 평가 (D-39): 관리자 판정을 정답으로 본 모델 버전별 혼동 행렬
-- 정답 = 관리자가 삭제/숨김했거나 REJECTED를 유지 → 부적절, 그 외 PUBLISHED 유지 또는 복구 → 정상
--       (정확한 라벨 규칙은 07b-ml-moderation 명세에서 확정)
SELECT mp.model_version,
       SUM(mp.image_label = 'FLAGGED' AND truth.bad = 1) AS tp,
       SUM(mp.image_label = 'FLAGGED' AND truth.bad = 0) AS fp,
       SUM(mp.image_label = 'SAFE'    AND truth.bad = 1) AS fn,
       SUM(mp.image_label = 'SAFE'    AND truth.bad = 0) AS tn
FROM ml_prediction mp JOIN (/* doodle_id별 관리자 판정 */) truth ON truth.doodle_id = mp.doodle_id
WHERE mp.outcome = 'OK'
GROUP BY mp.model_version;
```

- 모든 쿼리는 파라미터 바인딩을 씁니다(문자열 이어 붙이기 금지, SEC-01).
- `IN` 목록 길이는 `max-chunks`(60)로 제한합니다.
