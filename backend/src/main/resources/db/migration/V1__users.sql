-- 01-auth. Same DDL as docs/DB.md §2 `users`.
CREATE TABLE users (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  google_sub            VARCHAR(64)  NOT NULL,
  email                 VARCHAR(255) NOT NULL,
  nickname              VARCHAR(40)  NOT NULL,
  cursor_color          CHAR(7)      NOT NULL,
  role                  ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
  privacy_consented_at  DATETIME(3)  NULL,
  banned_at             DATETIME(3)  NULL,
  ban_reason            VARCHAR(500) NULL,
  last_posted_at        DATETIME(3)  NULL,
  created_at            DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_google_sub (google_sub),
  UNIQUE KEY uk_users_email (email),
  UNIQUE KEY uk_users_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
