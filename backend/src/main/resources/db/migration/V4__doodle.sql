-- 03-canvas-read. Same DDL as docs/DB.md §2 `doodle` and `doodle_chunk`; rows are inserted from 06-publish.
CREATE TABLE doodle (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  page_id           BIGINT      NOT NULL,
  author_id         BIGINT      NOT NULL,
  kind              ENUM('DRAWING','TEXT') NOT NULL,
  status            ENUM('PUBLISHED','REJECTED','HIDDEN','DELETED') NOT NULL,
  moderation_state  ENUM('PASSED','REJECTED','UNCHECKED') NOT NULL,
  moderation_detail JSON        NULL,
  bbox_x            INT         NOT NULL,
  bbox_y            INT         NOT NULL,
  bbox_w            INT         NOT NULL,
  bbox_h            INT         NOT NULL,
  payload           JSON        NOT NULL,
  payload_bytes     INT         NOT NULL,
  submitted_at      DATETIME(3) NOT NULL,
  published_at      DATETIME(3) NULL,
  removed_at        DATETIME(3) NULL,
  removed_by        BIGINT      NULL,
  PRIMARY KEY (id),
  KEY idx_doodle_page_status (page_id, status),
  KEY idx_doodle_author (author_id, submitted_at),
  KEY idx_doodle_moderation (moderation_state, submitted_at),
  CONSTRAINT fk_doodle_page   FOREIGN KEY (page_id)   REFERENCES page(id),
  CONSTRAINT fk_doodle_author FOREIGN KEY (author_id) REFERENCES users(id),
  CONSTRAINT ck_doodle_bbox CHECK (bbox_w BETWEEN 1 AND 1024 AND bbox_h BETWEEN 1 AND 1024)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per chunk the doodle's bbox touches (D-24).
CREATE TABLE doodle_chunk (
  page_id      BIGINT   NOT NULL,
  chunk_index  SMALLINT NOT NULL,
  doodle_id    BIGINT   NOT NULL,
  PRIMARY KEY (page_id, chunk_index, doodle_id),
  KEY idx_doodle_chunk_doodle (doodle_id),
  CONSTRAINT fk_dc_doodle FOREIGN KEY (doodle_id) REFERENCES doodle(id)
) ENGINE=InnoDB;
