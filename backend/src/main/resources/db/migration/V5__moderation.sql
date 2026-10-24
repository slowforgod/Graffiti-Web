-- 07-moderation frame (docs/specs/07-moderation-frame.md). Same DDL as docs/DB.md §2.
-- HELD: moderation was not sure or could not answer, so the doodle waits for an admin (D-42, PROPOSED).
ALTER TABLE doodle
  MODIFY status           ENUM('PUBLISHED','REJECTED','HELD','HIDDEN','DELETED') NOT NULL,
  MODIFY moderation_state ENUM('PASSED','REJECTED','HELD','UNCHECKED') NOT NULL;

-- Every AI, admin and system decision on a doodle or user (SEC-14). Rows are never updated or deleted.
CREATE TABLE moderation_action (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  actor_type       ENUM('AI','ADMIN','SYSTEM') NOT NULL,
  actor_id         BIGINT      NULL,
  action           ENUM('REJECT','HOLD','APPROVE','MARK_UNCHECKED','DELETE','HIDE','RESTORE','BAN','UNBAN') NOT NULL,
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
