-- 07b-ml-moderation (docs/specs/07b-ml-moderation.md). Same DDL as docs/DB.md §2.
-- One row per ML analysis of a drawing, for the SHADOW evaluation (D-39). No author columns (SEC-17).
CREATE TABLE ml_prediction (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  doodle_id         BIGINT       NOT NULL,
  model_version     VARCHAR(64)  NULL,
  mode              ENUM('SHADOW','ENFORCE') NOT NULL,
  outcome           ENUM('OK','TIMEOUT','ERROR') NOT NULL,
  image_label       ENUM('SAFE','FLAGGED') NULL,
  image_scores      JSON         NULL,
  ocr_text          VARCHAR(500) NULL,
  ocr_text_outcome  ENUM('NONE','PASS','REVIEW','BLOCK','ERROR','SKIPPED') NULL,
  ocr_text_category VARCHAR(40)  NULL,
  ocr_confidence    DECIMAL(4,3) NULL,
  latency_ms        INT          NULL,
  created_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_mlp_doodle (doodle_id),
  KEY idx_mlp_model_created (model_version, created_at),
  CONSTRAINT fk_mlp_doodle FOREIGN KEY (doodle_id) REFERENCES doodle(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
