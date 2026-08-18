-- ============================================================================
-- ConnectX Groups Initiative — Stage 4: group settings + user group-privacy
-- ============================================================================
-- Purely additive. Three new nullable-or-defaulted columns; no existing row's
-- current behavior changes as a result of applying this script.
--
-- WHAT THIS ADDS
-- 1. chat_groups.who_can_send_messages ENUM('EVERYONE','ADMINS_ONLY')
--    NOT NULL DEFAULT 'EVERYONE'
-- 2. chat_groups.who_can_edit_group_info ENUM('OWNER_ADMIN_ONLY','ALL_MEMBERS')
--    NOT NULL DEFAULT 'OWNER_ADMIN_ONLY'
--    Both are the remaining two of the "3 dynamic settings" CONNECTX_GROUP_ARCHITECTURE.md
--    §5.1 specifies for V1 (who_can_invite already exists from V1__connection_and_group_schema.sql
--    and is unaffected by this script) -- same column-per-policy convention, same table.
-- 3. users.group_add_privacy VARCHAR(20) NULL
--    "Who can add me to groups?" (CONNECTX_GROUP_ARCHITECTURE.md §8). Deliberately NULLable
--    with no DEFAULT and no backfill, mirroring the existing
--    users.profile_photo_visibility column exactly (same nullable-VARCHAR(20) shape, same
--    reason: 87 existing rows in connectx_db would otherwise need a backfill UPDATE just to
--    ship a new privacy default). NULL has exactly one server-side meaning -- "ANYONE" -- and
--    that interpretation lives in exactly one place (GroupAuthorizationService#resolveGroupAddPrivacy),
--    the same single-authority pattern ProfileVisibilityService already uses for the
--    identical null-means-EVERYONE case on profile_photo_visibility.
--
-- SAFETY
-- All three are additive ALTER TABLE ADD COLUMN statements against existing tables that already
-- have rows (chat_groups: 0 rows as of Stage 1-3, still zero application code creates GROUP
-- conversations in production data yet; users: 87 rows). None require an UPDATE/backfill step --
-- the two chat_groups columns get their DEFAULT for every existing (zero) row, and
-- group_add_privacy is simply NULL (== ANYONE) for every existing user until they explicitly set
-- it via PATCH /api/v1/users/me, exactly like profile_photo_visibility's rollout.
--
-- MECHANISM
-- Same idempotent-DDL pattern as V1/V3 (MySQL 8 has no `ADD COLUMN IF NOT EXISTS`): check
-- information_schema, then conditionally PREPARE/EXECUTE.
--
-- Apply manually, e.g.:
--   mysql -u root -p connectx_db < db/migrations/V4__group_settings_and_user_privacy.sql
--
-- Do not leave this applied against connectx_test_db between `mvn test` runs -- same caution as
-- V1/V3's header: ddl-auto=create-drop rebuilds chat_groups/users from entity metadata on every
-- run and has no knowledge of columns added by a manually-run script outside that metadata. The
-- entity mappings (ChatGroup and User, updated this same stage) generate the equivalent columns
-- automatically for connectx_test_db from Hibernate's own schema generation, so no manual step is
-- needed there at all.
-- ============================================================================

-- chat_groups.who_can_send_messages
SET @wcsm_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_groups' AND COLUMN_NAME = 'who_can_send_messages'
);
SET @ddl := IF(@wcsm_exists = 0,
  'ALTER TABLE chat_groups ADD COLUMN who_can_send_messages ENUM(''EVERYONE'',''ADMINS_ONLY'') NOT NULL DEFAULT ''EVERYONE''',
  'SELECT ''chat_groups.who_can_send_messages already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- chat_groups.who_can_edit_group_info
SET @wcegi_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_groups' AND COLUMN_NAME = 'who_can_edit_group_info'
);
SET @ddl := IF(@wcegi_exists = 0,
  'ALTER TABLE chat_groups ADD COLUMN who_can_edit_group_info ENUM(''OWNER_ADMIN_ONLY'',''ALL_MEMBERS'') NOT NULL DEFAULT ''OWNER_ADMIN_ONLY''',
  'SELECT ''chat_groups.who_can_edit_group_info already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- users.group_add_privacy
SET @gap_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'group_add_privacy'
);
SET @ddl := IF(@gap_exists = 0,
  'ALTER TABLE users ADD COLUMN group_add_privacy VARCHAR(20) NULL DEFAULT NULL',
  'SELECT ''users.group_add_privacy already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================================
-- End of V4__group_settings_and_user_privacy.sql
-- ============================================================================
