-- ============================================================================
-- ConnectX Groups Initiative — Stage 2: conversation_members uniqueness
-- ============================================================================
-- Purely additive. Adds a single UNIQUE KEY on conversation_members
-- (conversation_id, user_id).
--
-- WHY THIS IS NEEDED NOW
-- Flagged explicitly in Groups Stage 1.5 (see GroupAuthorizationService's
-- class-level javadoc and docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md) as a
-- prerequisite for the first stage that introduces CONCURRENT membership
-- creation for the same group: Stage 2's invitation-accept flow. Before this
-- stage, every conversation_members insert happened from a single-actor
-- operation (DIRECT creation under its own pessimistic per-pair lock, GROUP
-- creation for a brand-new conversation only the creator could reach) with no
-- concurrent writer to race against, so the absence of this constraint was
-- correctness-neutral. Two concurrent invitation-accept requests for the same
-- (group, user) pair are a real scenario starting this stage (e.g. a user
-- accepting from two open tabs, or a direct-add racing an in-flight accept),
-- and the DB-level constraint is this codebase's established backstop for
-- exactly that shape of race (see uk_connections_pair, uk_user_blocks_pair --
-- both added in V1 for the same reason).
--
-- SAFETY
-- Verified empirically against the live connectx_db before writing this
-- migration: zero (conversation_id, user_id) pairs currently have more than
-- one row (query: SELECT conversation_id, user_id, COUNT(*) FROM
-- conversation_members GROUP BY conversation_id, user_id HAVING COUNT(*) > 1
-- -- returned 0 rows). The invariant "at most one ConversationMember row per
-- (conversation, user) pair, active or soft-deleted" already holds for every
-- existing row (DIRECT creation never inserts a second row for a pair already
-- present; it restores the existing one instead -- see
-- ConversationService#forceRestoreConversationForUser), so this ALTER is safe
-- to apply against current data and requires no cleanup step first.
--
-- MECHANISM
-- Same idempotent-DDL pattern as V1's conversation_members ALTER statements
-- (MySQL 8 has no `ADD CONSTRAINT IF NOT EXISTS`): check information_schema,
-- then conditionally PREPARE/EXECUTE.
--
-- Apply manually, e.g.:
--   mysql -u root -p connectx_db < db/migrations/V3__group_membership_unique_constraint.sql
--
-- Do not leave this applied against connectx_test_db between `mvn test` runs
-- -- same caution as V1's header: ddl-auto=create-drop rebuilds
-- conversation_members from entity metadata on every run and has no knowledge
-- of a constraint added by a manually-run script outside that metadata. The
-- entity mapping (ConversationMember's @Table uniqueConstraints, added this
-- same stage) generates the equivalent constraint automatically for
-- connectx_test_db from Hibernate's own schema generation, so no manual step
-- is needed there at all.
-- ============================================================================

SET @uk_exists := (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_members'
    AND CONSTRAINT_NAME = 'uk_convmember_conversation_user'
);
SET @ddl := IF(@uk_exists = 0,
  'ALTER TABLE conversation_members ADD CONSTRAINT uk_convmember_conversation_user UNIQUE KEY (conversation_id, user_id)',
  'SELECT ''uk_convmember_conversation_user already exists, skipping'' AS notice'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================================
-- End of V3__group_membership_unique_constraint.sql
-- ============================================================================
