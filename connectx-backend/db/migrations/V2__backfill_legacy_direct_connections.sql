-- ============================================================================
-- ConnectX Groups Initiative — Legacy DIRECT Connection Backfill
-- ============================================================================
-- One-time, insert-only data backfill. Populates the `connections` table
-- (created by V1__connection_and_group_schema.sql) with a row for every
-- existing DIRECT conversation pair that predates the connection-request
-- system and therefore has no `connections` row today, so those users
-- participate in the connection model (and the Remove Connection feature)
-- consistently with everyone else.
--
-- This is a DEVELOPMENT/TEST-DATA operation, not a production consent claim.
-- See "WHAT LEGACY_BACKFILL MEANS" below before ever running this against
-- data that represents real user relationships.
--
-- BACKGROUND
-- The original architecture (ConnectX_Group_Architecture_Design.pdf) called
-- for this backfill as "PDF Stage 2 (backfill half)". It was never executed
-- -- the connection-required-for-new-DMs enforcement work (control-prompt
-- Stage 3, commit 02c3444) instead exempted pre-existing DIRECT conversations
-- from the connection check entirely, which is a different, non-destructive
-- mechanism (see ConversationService.createOrGetDirectConversation). That
-- exemption is untouched by this script and remains the reason old
-- conversations keep working even without a `connections` row -- this script
-- only adds data, it does not change what makes conversations usable.
--
-- SOURCE VALUE
-- Uses the existing ConnectionSource.MIGRATED enum value (already present in
-- both the Java enum and this table's native ENUM('REQUEST','MIGRATED')
-- column since V1 -- provisioned then, never previously used by any code
-- path). Deliberately NOT 'REQUEST': a MIGRATED row must never be
-- indistinguishable from an actual accepted connection request, since no
-- accept ever happened for these pairs. No schema/enum change was needed or
-- made by this script.
--
-- WHAT LEGACY_BACKFILL (source = MIGRATED) MEANS
-- A `connections` row with source = MIGRATED means: "these two users already
-- had a DIRECT conversation before the connection-request system existed,
-- and this development/test database's data was migrated into the new
-- connection model for consistency." It does NOT mean the user on either
-- side ever explicitly sent or accepted a connection request. Do not treat
-- MIGRATED rows as evidence of explicit consent in any future feature (e.g.
-- "mutual connections", audit/compliance surfaces) without accounting for
-- this distinction. This script exists because the current ConnectX database
-- is test/development data; it must not be re-run unreviewed against a
-- database containing real user relationships without re-confirming this
-- consent caveat still holds.
--
-- MIGRATION MECHANISM
-- Standalone SQL artifact, same convention as V1 -- not Flyway (none in this
-- project), not wired into application startup (no ApplicationRunner/
-- BeanPostProcessor), and not automatically executed. Apply manually:
--   mysql -u root -p connectx_db < db/migrations/V2__backfill_legacy_direct_connections.sql
--
-- SCOPE / SAFETY
-- - Reads only `conversations` (type = 'DIRECT' only -- GROUP conversations,
--   if any exist, are completely ignored), `conversation_members`, and the
--   existing `connections` table.
-- - Writes only new rows into `connections`. Never updates or deletes an
--   existing `connections` row -- an existing REQUEST-sourced connection
--   (e.g. one created through the real send/accept flow) is left completely
--   untouched: not its source, not its created_at, not its id.
-- - Never touches conversations, conversation_members, messages (ciphertext/
--   nonce/encryptionAlgorithm or otherwise), users, connection_requests,
--   user_blocks, or any E2EE/push/WebSocket state.
-- - The self-join condition (cm2.user_id > cm1.user_id) structurally
--   guarantees user_id_low < user_id_high for every candidate row -- the
--   same canonical-order invariant `chk_connections_canonical_order` already
--   enforces -- so this can never attempt a self-connection or a
--   reversed-order duplicate of an existing row.
-- - DISTINCT + a NOT EXISTS anti-join against `connections` makes this
--   idempotent: a pair that already has a `connections` row (MIGRATED or
--   REQUEST) is never selected as a candidate again. INSERT IGNORE is used
--   as defense-in-depth on top of that (so an unanticipated race or
--   constraint edge case skips that one row instead of aborting the whole
--   statement) -- under normal conditions it changes nothing, since the
--   NOT EXISTS filter and this table's own UNIQUE/CHECK constraints already
--   make duplicates and self-pairs unreachable.
--
-- IDEMPOTENCY
-- Running this script twice produces identical end state: the second run's
-- INSERT IGNORE ... SELECT matches zero candidate rows (every pair it would
-- have considered now already exists in `connections`), so it inserts
-- nothing and modifies nothing.
-- ============================================================================

INSERT IGNORE INTO connections (user_id_low, user_id_high, source, created_at)
SELECT DISTINCT
    LEAST(cm1.user_id, cm2.user_id)    AS user_id_low,
    GREATEST(cm1.user_id, cm2.user_id) AS user_id_high,
    'MIGRATED'                         AS source,
    NOW(6)                             AS created_at
FROM conversations c
JOIN conversation_members cm1
    ON cm1.conversation_id = c.id
JOIN conversation_members cm2
    ON cm2.conversation_id = c.id
   AND cm2.user_id > cm1.user_id
WHERE c.type = 'DIRECT'
  AND NOT EXISTS (
      SELECT 1 FROM connections existing
      WHERE existing.user_id_low = LEAST(cm1.user_id, cm2.user_id)
        AND existing.user_id_high = GREATEST(cm1.user_id, cm2.user_id)
  );
