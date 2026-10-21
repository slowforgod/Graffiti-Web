-- 02-page-core. Same DDL as docs/DB.md §2 `page`.
CREATE TABLE page (
  id                           BIGINT      NOT NULL AUTO_INCREMENT,
  seq                          INT         NOT NULL,
  status                       ENUM('ACTIVE','COUNTDOWN','ENDED') NOT NULL,
  -- At most one page that has not ended (INV-11): ENDED rows get NULL and escape the unique key.
  live_flag                    TINYINT AS (CASE WHEN status <> 'ENDED' THEN 1 ELSE NULL END) STORED,
  width                        INT         NOT NULL,
  height                       INT         NOT NULL,
  chunk_size                   INT         NOT NULL,
  cell_size                    INT         NOT NULL,
  countdown_threshold_permille SMALLINT    NOT NULL,
  countdown_duration_sec       INT         NOT NULL,
  max_lifetime_sec             INT         NOT NULL,
  created_at                   DATETIME(3) NOT NULL,
  countdown_started_at         DATETIME(3) NULL,
  scheduled_end_at             DATETIME(3) NOT NULL,
  ended_at                     DATETIME(3) NULL,
  end_reason                   ENUM('OCCUPANCY','MAX_LIFETIME') NULL,
  final_occupancy_permille     SMALLINT    NULL,
  final_occupancy_bitmap       VARBINARY(4096) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_page_seq (seq),
  UNIQUE KEY uk_page_live (live_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
