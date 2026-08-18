-- ============================================================================
-- ConnectX Groups Initiative — Group E2EE messaging: wrapper identity column
-- ============================================================================
-- Purely additive, single nullable column. Genuinely required for correctness,
-- not a redesign: ECDH-based unwrap (see keyManager/encryption.ts,
-- decryption.ts, reused as-is for group-key wrapping) needs the WRAPPING
-- user's public key to re-derive the same shared secret the wrapper used --
-- GroupMemberKeyDto previously only carried the wrapped bytes themselves, with
-- no way for the unwrapping client to know whose public key to fetch. This
-- column records who wrapped a given row's key so the unwrapping client can
-- look up that user's public key (deviceApi.getUserPublicKeys) before calling
-- decryptMessage(myPrivateKey, wrapperPublicKey, wrappedKey, wrapNonce).
--
-- Always set server-side from the authenticated submitting principal
-- (GroupKeyController's @AuthenticationPrincipal), never client-supplied --
-- same actor-identity convention as every other Groups mutation.
--
-- Nullable (no backfill needed): group_member_keys was empty in connectx_db
-- at the time this was added (Stage 6C shipped with zero real key rows;
-- verified live-test rows from later UI stages were cleaned up afterward per
-- their own memory notes), so there is nothing to backfill either way.
--
-- MECHANISM: same idempotent-DDL pattern as V1/V3/V4/V5.
--
-- Apply manually, e.g.:
--   mysql -u root -p connectx_db < db/migrations/V6__group_member_key_wrapper.sql
--
-- Do not leave this applied against connectx_test_db between `mvn test` runs
-- -- same caution as prior migrations' headers: ddl-auto=create-drop rebuilds
-- group_member_keys from entity metadata (GroupMemberKey.wrappedByUserId,
-- added this same change) on every run, so no manual step is needed there.
-- ============================================================================

SET @wbu_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'group_member_keys' AND COLUMN_NAME = 'wrapped_by_user_id'
);
SET @ddl := IF(@wbu_exists = 0,
  'ALTER TABLE group_member_keys ADD COLUMN wrapped_by_user_id BIGINT NULL DEFAULT NULL',
  'SELECT ''group_member_keys.wrapped_by_user_id already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists := (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'group_member_keys' AND CONSTRAINT_NAME = 'fk_groupmemberkey_wrapper'
);
SET @ddl := IF(@fk_exists = 0,
  'ALTER TABLE group_member_keys ADD CONSTRAINT fk_groupmemberkey_wrapper FOREIGN KEY (wrapped_by_user_id) REFERENCES users (id) ON DELETE SET NULL',
  'SELECT ''fk_groupmemberkey_wrapper already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================================
-- End of V6__group_member_key_wrapper.sql
-- ============================================================================
