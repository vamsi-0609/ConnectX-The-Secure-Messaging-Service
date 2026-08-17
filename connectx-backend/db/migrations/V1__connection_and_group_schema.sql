-- ============================================================================
-- ConnectX Groups Initiative — Stage 0B: Relationship & Group Schema
-- ============================================================================
-- Purely additive schema preparation for the future Connections, Blocking,
-- and Groups features (see ConnectX_Group_Architecture_Design.pdf, Sections
-- 4, 6, 7, 9). No application code reads or writes any of this yet.
--
-- MIGRATION MECHANISM
-- This project has no Flyway/Liquibase (verified: no such dependency in
-- pom.xml; schema is otherwise managed entirely by Hibernate's
-- ddl-auto=update against whatever entities exist). This script is a
-- standalone, manually-applied SQL artifact. It deliberately lives OUTSIDE
-- src/main/resources/db/migration (Flyway's default scan path) so it can
-- never be silently auto-executed if Flyway is adopted in a later stage
-- without an explicit decision to do so. It is named in Flyway's
-- V{version}__{description}.sql convention purely so it can be adopted as
-- the baseline migration with minimal friction if/when that happens.
--
-- Apply manually, e.g.:
--   mysql -u root -p connectx_db < db/migrations/V1__connection_and_group_schema.sql
--
-- DO NOT leave this schema applied in connectx_test_db between `mvn test`
-- runs. connectx_test_db uses ddl-auto=create-drop: at SessionFactory
-- shutdown Hibernate issues DROP TABLE for every table it manages
-- (users, conversations, conversation_members, ...), in an order it computes
-- from ITS OWN entity graph. It has no knowledge of the tables created by
-- this script, so it cannot sequence around their foreign keys -- verified
-- empirically that MySQL then correctly refuses the drop:
--   ERROR 3730: Cannot drop table 'users' referenced by a foreign key
--   constraint 'fk_connreq_requester' on table 'connection_requests'.
-- This would break every single existing test on the next `mvn test` run.
-- If you need to manually verify this script against connectx_test_db,
-- drop the resulting tables again afterward, before running `mvn test`.
-- connectx_db uses ddl-auto=update, which only ever adds -- it never drops
-- an existing table -- so this failure mode does not apply there, and this
-- script's permanent home is connectx_db.
--
-- IDEMPOTENCY
-- CREATE TABLE statements use IF NOT EXISTS (native MySQL 8 syntax; this
-- also covers every inline index/constraint in one shot, since the whole
-- statement is skipped if the table already exists). The conversation_members
-- ALTER TABLE statements target an EXISTING table and cannot use
-- IF NOT EXISTS -- verified empirically against this server (MySQL 8.0.46)
-- that MySQL, unlike MariaDB, does not support `ADD COLUMN IF NOT EXISTS` or
-- `ADD CONSTRAINT IF NOT EXISTS` (both produce ERROR 1064). They instead use
-- the standard MySQL idempotent-DDL pattern: check information_schema, then
-- conditionally PREPARE/EXECUTE the ALTER statement.
--
-- GENERATED COLUMNS: VIRTUAL, NOT STORED
-- The two partial-unique-on-PENDING columns (pending_pair_key,
-- pending_invite_key) are VIRTUAL, not STORED. Verified empirically that
-- InnoDB rejects ON DELETE CASCADE (ERROR 1215, "Cannot add foreign key
-- constraint") on any foreign key whose column feeds a STORED generated
-- column -- this would have silently forced a choice between the required
-- partial-unique constraint and the required CASCADE behavior on
-- group_invitations. A VIRTUAL generated column has no such restriction
-- (confirmed empirically, including a live INSERT + parent DELETE proving
-- the cascade actually fires correctly) and MySQL 8.0.13+/InnoDB fully
-- supports a UNIQUE secondary index on a VIRTUAL column, so both
-- requirements are satisfied simultaneously with no compromise.
--
-- NAMING NOTE
-- The PDF's proposed table name `groups` is a MySQL 8.0 reserved word
-- (added for window-function frame syntax, e.g. `GROUPS BETWEEN`) and fails
-- as an unquoted identifier -- verified empirically (ERROR 1064). Renamed to
-- `chat_groups` here, per explicit approval; no other design or behavior
-- change. This has zero knock-on effect on any other table: per spec,
-- group_invitations.group_id references conversations.id directly, not
-- chat_groups, so no other FK target changes.
--
-- SCOPE
-- Schema only. No entities, repositories, services, or controllers exist for
-- any of this yet. ddl-auto=update only manages tables mapped to known JPA
-- entities, so it will not touch, alter, or drop anything created here.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. connection_requests
--    PENDING/ACCEPTED/REJECTED/CANCELLED workflow/audit trail. Rows are never
--    hard-deleted; a new request after a terminal state inserts a new row.
--    "Partial unique on (requester_id, recipient_id) WHERE status='PENDING'"
--    is emulated via a VIRTUAL generated column that evaluates to NULL for
--    any non-PENDING row -- MySQL unique indexes allow unlimited NULLs, so
--    this blocks a duplicate concurrent PENDING request for the same ordered
--    pair while leaving unlimited REJECTED/CANCELLED/ACCEPTED history rows
--    unconstrained. Verified empirically against this exact pattern.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS connection_requests (
  id BIGINT NOT NULL AUTO_INCREMENT,
  requester_id BIGINT NOT NULL,
  recipient_id BIGINT NOT NULL,
  status ENUM('PENDING','ACCEPTED','REJECTED','CANCELLED') NOT NULL,
  created_at DATETIME(6) NOT NULL,
  responded_at DATETIME(6) DEFAULT NULL,
  pending_pair_key VARCHAR(41) GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING' THEN CONCAT(requester_id, '_', recipient_id) ELSE NULL END
  ) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_connreq_pending_pair (pending_pair_key),
  KEY idx_connreq_recipient_status (recipient_id, status),
  KEY idx_connreq_requester (requester_id),
  CONSTRAINT fk_connreq_requester FOREIGN KEY (requester_id) REFERENCES users (id),
  CONSTRAINT fk_connreq_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
  CONSTRAINT chk_connreq_not_self CHECK (requester_id <> recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 2. connections
--    Fast, symmetric "are these two users connected" fact table -- one row
--    per unordered pair. Canonical ordering (user_id_low < user_id_high) is
--    enforced at the DB level via a CHECK constraint, not left to
--    application code alone -- structurally guarantees no duplicate or
--    reversed-direction row, and no self-connection row, can ever exist.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS connections (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id_low BIGINT NOT NULL,
  user_id_high BIGINT NOT NULL,
  source ENUM('REQUEST','MIGRATED') NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_connections_pair (user_id_low, user_id_high),
  KEY idx_connections_low (user_id_low),
  KEY idx_connections_high (user_id_high),
  CONSTRAINT fk_connections_user_low FOREIGN KEY (user_id_low) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_connections_user_high FOREIGN KEY (user_id_high) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT chk_connections_canonical_order CHECK (user_id_low < user_id_high)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 3. user_blocks
--    Directional block, independent of connections (blocking a connection
--    does not delete the connections row -- it only gates enforcement on
--    top of it, per PDF Section 4.2).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_blocks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  blocker_id BIGINT NOT NULL,
  blocked_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_blocks_pair (blocker_id, blocked_id),
  KEY idx_user_blocks_blocked (blocked_id),
  CONSTRAINT fk_user_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_user_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT chk_user_blocks_not_self CHECK (blocker_id <> blocked_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 4. chat_groups  (PDF calls this table `groups` -- renamed, see header note)
--    Group-specific metadata. Shares its primary key with conversations.id
--    (conversation_id is both PK and FK) rather than having its own surrogate
--    identity, so "conversationId" stays the single universal handle the rest
--    of the app already uses everywhere.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_groups (
  conversation_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) DEFAULT NULL,
  avatar_url VARCHAR(255) DEFAULT NULL,
  created_by_user_id BIGINT NOT NULL,
  who_can_invite ENUM('OWNER_ADMIN_ONLY','ALL_MEMBERS') NOT NULL DEFAULT 'OWNER_ADMIN_ONLY',
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (conversation_id),
  KEY idx_chat_groups_created_by (created_by_user_id),
  CONSTRAINT fk_chat_groups_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_groups_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 5. group_invitations
--    Invite/accept workflow. group_id references conversations.id directly
--    (the group's conversation row) per spec, not chat_groups.conversation_id
--    -- both hold the same value, and conversations is the more primary,
--    pre-existing table. Same partial-unique-on-PENDING technique as
--    connection_requests.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS group_invitations (
  id BIGINT NOT NULL AUTO_INCREMENT,
  group_id BIGINT NOT NULL,
  invitee_user_id BIGINT NOT NULL,
  invited_by_user_id BIGINT NOT NULL,
  status ENUM('PENDING','ACCEPTED','REJECTED','CANCELLED') NOT NULL,
  created_at DATETIME(6) NOT NULL,
  responded_at DATETIME(6) DEFAULT NULL,
  pending_invite_key VARCHAR(41) GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING' THEN CONCAT(group_id, '_', invitee_user_id) ELSE NULL END
  ) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_groupinv_pending_pair (pending_invite_key),
  KEY idx_groupinv_invitee (invitee_user_id),
  KEY idx_groupinv_invited_by (invited_by_user_id),
  CONSTRAINT fk_groupinv_group FOREIGN KEY (group_id) REFERENCES conversations (id) ON DELETE CASCADE,
  CONSTRAINT fk_groupinv_invitee FOREIGN KEY (invitee_user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_groupinv_invited_by FOREIGN KEY (invited_by_user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT chk_groupinv_not_self CHECK (invitee_user_id <> invited_by_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ----------------------------------------------------------------------------
-- 6. conversation_members additions -- two nullable, additive columns.
--    NULL for every existing row (and every future DIRECT row); only
--    meaningful for GROUP conversations once a later stage assigns roles.
--    No enforcement of any kind happens in this stage.
--
--    invited_by_user_id carries NO explicit ON DELETE clause, matching this
--    table's two existing FKs (conversation_id, user_id) -- verified via
--    SHOW CREATE TABLE that neither carries an ON DELETE action today
--    (default RESTRICT). Preserves existing relationship semantics exactly,
--    per the Stage 0B mandate not to alter them.
-- ----------------------------------------------------------------------------

-- role
SET @role_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_members' AND COLUMN_NAME = 'role'
);
SET @ddl := IF(@role_exists = 0,
  'ALTER TABLE conversation_members ADD COLUMN role ENUM(''OWNER'',''ADMIN'',''MEMBER'') NULL DEFAULT NULL',
  'SELECT ''conversation_members.role already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- invited_by_user_id
SET @invited_by_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_members' AND COLUMN_NAME = 'invited_by_user_id'
);
SET @ddl := IF(@invited_by_exists = 0,
  'ALTER TABLE conversation_members ADD COLUMN invited_by_user_id BIGINT NULL DEFAULT NULL',
  'SELECT ''conversation_members.invited_by_user_id already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- FK for invited_by_user_id (added after the column exists, so it must be a
-- separate conditional ALTER rather than inline on the ADD COLUMN above)
SET @fk_exists := (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_members' AND CONSTRAINT_NAME = 'fk_convmember_invited_by'
);
SET @ddl := IF(@fk_exists = 0,
  'ALTER TABLE conversation_members ADD CONSTRAINT fk_convmember_invited_by FOREIGN KEY (invited_by_user_id) REFERENCES users (id)',
  'SELECT ''fk_convmember_invited_by already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- supporting index (an FK column benefits from one; naming it explicitly
-- avoids InnoDB silently generating an unnamed one)
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_members' AND INDEX_NAME = 'idx_convmember_invited_by'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE conversation_members ADD INDEX idx_convmember_invited_by (invited_by_user_id)',
  'SELECT ''idx_convmember_invited_by already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================================
-- End of V1__connection_and_group_schema.sql
-- ============================================================================
