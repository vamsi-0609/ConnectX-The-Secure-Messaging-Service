-- ============================================================================
-- ConnectX Groups Initiative — Stage 6B: Group E2EE database/key model
-- ============================================================================
-- Purely additive. Establishes ONLY the schema/model for the approved Group
-- E2EE V1 design (one random AES-256 group key per group, wrapped per member,
-- key versioning so removed/left members lose access on rotation). No key
-- generation, wrapping, rotation, or message-encryption behavior is
-- implemented by this script or anything it's paired with this stage --
-- see GroupMemberKey / ChatGroup.keyVersion / Message.groupKeyVersion.
--
-- WHAT THIS ADDS
-- 1. chat_groups.key_version INT NOT NULL DEFAULT 1
--    Authoritative current group-key version. Every existing (and future)
--    group row resolves to 1 until a later stage implements rotation.
-- 2. messages.group_key_version INT NULL
--    NULL for DIRECT messages always; will hold the key version a GROUP
--    message was encrypted under, once a later stage starts populating it.
--    No backfill -- existing rows (all DIRECT, see verification below) stay
--    NULL, which is exactly their correct value.
-- 3. group_member_keys -- new table. One row per (group, member): the
--    member's opaque wrapped copy of the group's CURRENT key. No plaintext
--    key material of any kind lives here or anywhere server-side. Overwritten
--    in place on rotation (a future stage), not versioned history -- old
--    messages stay decrypted client-side already (existing decrypted-message
--    cache), so the server never needs to hand out a historical key.
--
-- SAFETY (verified against the live connectx_db immediately before writing
-- this script)
-- chat_groups: 0 rows. conversations WHERE type='GROUP': 0 rows. messages
-- (DIRECT): 2228 rows, all pre-existing and unaffected (group_key_version is
-- nullable with no default, so every existing row is simply NULL). messages
-- (GROUP): 0 rows. conversation_members duplicate (conversation_id, user_id)
-- pairs: 0. No application code creates GROUP conversations, GROUP messages,
-- or reads/writes any of these three additions yet -- this is schema/model
-- only, exactly like Stage 0B before it.
--
-- MECHANISM
-- Same idempotent-DDL pattern as V1/V3/V4 (MySQL 8 has no
-- `ADD COLUMN IF NOT EXISTS`): check information_schema, then conditionally
-- PREPARE/EXECUTE. The new table uses native `CREATE TABLE IF NOT EXISTS`.
--
-- FK/CASCADE CONVENTIONS
-- group_member_keys.conversation_id -> chat_groups.conversation_id ON DELETE
-- CASCADE, and .member_user_id -> users.id ON DELETE CASCADE -- matches
-- group_invitations' precedent (V1__connection_and_group_schema.sql), the
-- established rule for junction/membership-shaped tables in this Groups
-- schema (chat_groups itself also cascades from conversations). This exact
-- table shape (columns, FK targets, ON DELETE CASCADE on both FKs, unique
-- key on (conversation_id, member_user_id)) was pre-approved in
-- docs/CONNECTX_GROUP_ARCHITECTURE.md §21 during the architecture review;
-- this script adds wrap_nonce (kept separate from wrapped_key, since
-- AES-256-GCM wrapping needs its own nonce distinct from any per-message
-- nonce) per this stage's explicit instructions, which the §21 sketch didn't
-- separately break out.
--
-- Apply manually, e.g.:
--   mysql -u root -p connectx_db < db/migrations/V5__group_e2ee_key_model.sql
--
-- Do not leave this applied against connectx_test_db between `mvn test` runs
-- -- same caution as V1/V3/V4's header: ddl-auto=create-drop rebuilds
-- chat_groups/messages/group_member_keys from entity metadata on every run.
-- The entity mappings (ChatGroup.keyVersion, Message.groupKeyVersion, and
-- the new GroupMemberKey entity, all added this same stage) generate the
-- equivalent columns/table automatically for connectx_test_db from
-- Hibernate's own schema generation, so no manual step is needed there.
-- ============================================================================

-- chat_groups.key_version
SET @kv_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_groups' AND COLUMN_NAME = 'key_version'
);
SET @ddl := IF(@kv_exists = 0,
  'ALTER TABLE chat_groups ADD COLUMN key_version INT NOT NULL DEFAULT 1',
  'SELECT ''chat_groups.key_version already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- messages.group_key_version
SET @gkv_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND COLUMN_NAME = 'group_key_version'
);
SET @ddl := IF(@gkv_exists = 0,
  'ALTER TABLE messages ADD COLUMN group_key_version INT NULL DEFAULT NULL',
  'SELECT ''messages.group_key_version already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- group_member_keys
CREATE TABLE IF NOT EXISTS group_member_keys (
  id BIGINT NOT NULL AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  member_user_id BIGINT NOT NULL,
  wrapped_key TEXT NOT NULL,
  wrap_nonce TEXT NOT NULL,
  key_version INT NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_groupmemberkey_conversation_member (conversation_id, member_user_id),
  KEY idx_groupmemberkey_member (member_user_id),
  CONSTRAINT fk_groupmemberkey_conversation FOREIGN KEY (conversation_id) REFERENCES chat_groups (conversation_id) ON DELETE CASCADE,
  CONSTRAINT fk_groupmemberkey_member FOREIGN KEY (member_user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- End of V5__group_e2ee_key_model.sql
-- ============================================================================
