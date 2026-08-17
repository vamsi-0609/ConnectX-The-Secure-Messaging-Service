# ConnectX Group Implementation — Persistent State

Durable handoff document for the ConnectX Groups initiative. Updated after every completed stage so
implementation can continue safely even across a fresh conversation/context reset.

**Architecture source of truth:** `ConnectX_Group_Architecture_Design.pdf` (repo root), inspected
2026-08-17 against branch `feature/pwa-notifications`. Every "what exists today" claim in that document
was verified by direct code inspection at that time; treat it as a snapshot, not an eternal truth — each
stage below re-verifies the specific claims it depends on before acting on them.

## Stage mapping (control prompt ↔ PDF)

| Control-prompt stage | PDF section/stage | Summary |
|---|---|---|
| 0A | — (not in PDF) | Git/checkpoint/baseline verification |
| 0B | Stage 0 | Database/schema preparation only |
| 1 | Stage 1 (backend) | Connection backend |
| 1.5 | Stage 1 (frontend) | Connection frontend |
| 2 | Stage 2 (backfill half) | Existing DIRECT conversation migration/backfill |
| 3 | Stage 2 (enforcement half) | Enable connection-required DM enforcement |
| 4 | Stage 3 | Blocking |
| 5 | Stage 4 | Group CRUD / membership foundation |
| 6 | Stage 5 | Group invitations |
| 7 | Stage 6 | Group roles and permissions hardening |
| 8 | Stage 7 (prerequisite) | WebSocket SUBSCRIBE-time authorization fix |
| 9 | Stage 7 (rest) | Group messaging + Phase-2 per-recipient encryption |
| 10 | Stage 9 | PWA / Web Push group integration |
| 11 | Stage 10 | Final regression/security audit |

PDF Stage 8 (Phase-3 crypto planning) is a decoupled design spike, not sequenced into the control-prompt
stage list above; revisit only if explicitly requested later.

## Current status

**Current stage: 0B — Database/schema preparation only — COMPLETE**
**Next stage: 1 — Connection backend**

---

## Stage 0A — Git/checkpoint/baseline verification

### What was done
- Verified git repo state: branch `feature/pwa-notifications`, up to date with `origin`, working tree
  clean except one untracked file (the architecture PDF).
- Verified backend baseline:
  - `mvn clean compile` → success, no errors.
  - `mvn test` → **BUILD SUCCESS**, **25/25 tests passed, 0 failures, 0 errors**, across 9 test classes:
    `OtpRateLimitIntegrationTest`, `ConversationDeletionCleanupTest`, `ConversationServiceStage3Test`,
    `LocalMediaStorageValidationTest`, `MessagePushPreviewTest`, `ReactionRaceIntegrationTest`,
    `ReactionVisibilityTest`, `StarRaceIntegrationTest`, `WebPushServiceCleanupTest`.
  - Noted: the race-condition tests log expected `SQLIntegrityConstraintViolationException`
    ("Duplicate entry") lines at ERROR level as part of their normal, caught control flow (testing the
    `REQUIRES_NEW`-isolated-insert pattern) — this is pre-existing log noise, not a failure signal.
- Verified frontend baseline:
  - `npx tsc --noEmit` → 0 type errors.
  - `npm run build` → success (Vite production build, 1653 modules transformed).
- Verified environment: Java 25 LTS, Maven 3.9.16, Node 22.14.0, MySQL80 Windows service running.
  Backend uses real MySQL for both main (`connectx_db`) and test (`connectx_test_db`) profiles — not
  H2 — consistent with the PDF's Section 17 testing-strategy claim (H2's differing isolation level was
  why a prior bug went undetected by unit tests).
- Cross-checked several load-bearing PDF claims directly against current source (all confirmed true as
  of this stage):
  - `ConversationType.java` contains exactly `DIRECT, GROUP`, with `GROUP` unused elsewhere in the
    backend (PDF §2.2/§6.1).
  - No Flyway dependency in `connectx-backend/pom.xml` (PDF §5.3).
  - `application.yml` confirms `spring.jpa.hibernate.ddl-auto: update` (PDF §5, §16.1).
  - `application-test.yml` confirms tests run against a dedicated MySQL database with
    `ddl-auto: create-drop`, not H2.
- Added this state document and checked the architecture PDF into version control as the durable
  source of truth for the initiative.

### Files changed this stage
- Added: `docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md` (this file)
- Added: `ConnectX_Group_Architecture_Design.pdf` (was untracked; now the checked-in source of truth)

### Database changes
None.

### API changes
None.

### Frontend changes
None.

### WebSocket changes
None.

### Tests executed
- Backend: `mvn test` — 25/25 passed, 0 failures, 0 errors.
- Frontend: `npx tsc --noEmit` (clean) and `npm run build` (succeeded).

### Build result
Backend: **BUILD SUCCESS**. Frontend: **BUILD SUCCESS** (`tsc && vite build`).

### Git commit hash
`6afa0be` — `chore(connectx): Stage 0A baseline checkpoint`

### Known issues (pre-existing, not introduced or fixed this stage)
- Mail credentials are committed in plaintext in `connectx-backend/src/main/resources/application.yml`
  (lines ~48-49). Out of scope for this initiative — not modified, not to be modified without a separate,
  explicit instruction.
- Per the PDF (citing the prior 2026-08-16 architecture audit): E2EE private key is escrowed
  server-side (`User.masterPrivateKey`), and WebSocket `/topic/conversation/{id}` has no SUBSCRIBE-time
  authorization check. Both are known and already scheduled: the WS fix is control-prompt Stage 8
  (mandatory prerequisite of Stage 9's group messaging); the identity-key escrow issue is out of scope
  for Phases 1–2 of Group E2EE per PDF §13.4 and is explicitly not touched by this initiative.

### Security considerations
None — no functional code changed this stage; this was verification-only.

### Regression verification
Full existing backend suite (25 tests) and frontend type-check/build confirmed green before any
Groups-related change begins. This is the baseline every later stage's regression check is measured
against.

### Rollback procedure
Revert the single Stage 0A commit. No schema, API, or runtime behavior was changed.

### Next stage
Stage 0B (below).

---

## Stage 0B — Database/schema preparation only

### What was done
Inspected the live schema directly (not just entity annotations) before writing any DDL:
- Confirmed `users.id`, `conversations.id`, `conversation_members.id` are all `BIGINT NOT NULL
  AUTO_INCREMENT`; timestamps are `datetime(6)`; charset/collation is `utf8mb4`/`utf8mb4_0900_ai_ci`.
- Confirmed the codebase's own `@Enumerated(EnumType.STRING)` fields (`Conversation.type`,
  `Message.messageType`) are materialized as native MySQL `ENUM(...)` columns by Hibernate, not
  `VARCHAR` — matched this convention for the new `status`/`role`/`source`/`who_can_invite` columns so a
  future Stage 1+ JPA entity won't collide with `ddl-auto=update`.
- Confirmed every existing table (including pure junction tables `message_reactions`, `message_stars`)
  uses a surrogate `id BIGINT AUTO_INCREMENT` primary key, even where a natural composite key exists —
  followed this convention for `connections` (surrogate `id` + `UNIQUE KEY` on `(user_id_low,
  user_id_high)`) rather than a literal composite PK.
- Confirmed neither of `conversation_members`'s two existing FKs (`conversation_id`, `user_id`) carries
  an `ON DELETE` clause (default `RESTRICT`) — matched this for the new `invited_by_user_id` FK.
- Reviewed `MessageStarSchemaPreparer.java` (existing precedent for raw-SQL schema work in this
  codebase) — confirmed it's a `BeanPostProcessor` hooked on the `DataSource` bean; deliberately did
  **not** follow that pattern here, since Stage 0B forbids any new application/wired behavior. This
  script is a standalone artifact instead (see below).

**Two blocking findings surfaced during inspection, both resolved before proceeding:**

1. **`groups` is a MySQL 8.0 reserved word** (added for window-function frame syntax). Verified
   empirically: `CREATE TABLE groups (...)` fails with `ERROR 1064`. Stopped and reported this to the
   user with options; **approved fix: renamed the table to `chat_groups`.** Zero knock-on effect — per
   spec, `group_invitations.group_id` references `conversations.id` directly, not the groups table, so
   no other table's FK target changed.
2. **InnoDB rejects `ON DELETE CASCADE` on a foreign key whose column feeds a `STORED` generated
   column** (`ERROR 1215`, discovered while building the partial-unique-on-PENDING index for
   `group_invitations`, which needed both a generated-column unique index over `(group_id,
   invitee_user_id)` *and* `ON DELETE CASCADE` from both `conversations` and `users`). Resolved without
   compromise by using `VIRTUAL` instead of `STORED` generated columns — verified empirically
   (including a live `INSERT` + parent `DELETE`) that `VIRTUAL` has no such restriction and the cascade
   fires correctly. Applied to both `connection_requests.pending_pair_key` and
   `group_invitations.pending_invite_key` for consistency.

**A third, non-blocking operational finding:** applying this schema to `connectx_test_db` and leaving it
there would break `mvn test`. `connectx_test_db` uses `ddl-auto=create-drop`; at shutdown Hibernate
issues `DROP TABLE` for every table *it* manages, in an order computed from its own entity graph — it
has no knowledge of externally-created tables, so it can't sequence around their FKs. Verified
empirically: `DROP TABLE users` was rejected with `ERROR 3730` ("referenced by a foreign key constraint
'fk_connreq_requester' on table 'connection_requests'") once the new schema was present. Resolution:
this script's permanent home is **`connectx_db`** (`ddl-auto=update`, which only ever adds, never drops
— immune to this failure mode). `connectx_test_db` was reset to empty after verification and is left
untouched by this stage; the full backend suite was then re-run against a clean bootstrap to confirm.

Every other design decision (partial-unique-on-PENDING via a generated column, `CHECK` constraints for
canonical connection ordering and self-block/self-request/self-invite prevention, cascade rules) was
verified empirically against the real MySQL 8.0.46 server before being written into the final script —
see the script's own header comments for the reasoning behind each.

### Exact schema created

**New tables** (all `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`):

| Table | Key columns | Constraints |
|---|---|---|
| `connection_requests` | `id` PK, `requester_id`/`recipient_id` FK→`users.id`, `status` ENUM(PENDING/ACCEPTED/REJECTED/CANCELLED), `created_at`, `responded_at`, `pending_pair_key` (VIRTUAL generated) | `UNIQUE(pending_pair_key)` = partial-unique-on-PENDING; `KEY(recipient_id,status)`; `KEY(requester_id)`; `CHECK(requester_id<>recipient_id)` |
| `connections` | `id` PK, `user_id_low`/`user_id_high` FK→`users.id` ON DELETE CASCADE, `source` ENUM(REQUEST/MIGRATED), `created_at` | `UNIQUE(user_id_low,user_id_high)`; `CHECK(user_id_low<user_id_high)` (canonical order, DB-enforced) |
| `user_blocks` | `id` PK, `blocker_id`/`blocked_id` FK→`users.id` ON DELETE CASCADE, `created_at` | `UNIQUE(blocker_id,blocked_id)`; `KEY(blocked_id)`; `CHECK(blocker_id<>blocked_id)` |
| `chat_groups` (PDF: `groups`, renamed — reserved word) | `conversation_id` PK/FK→`conversations.id` ON DELETE CASCADE, `name`, `description`, `avatar_url`, `created_by_user_id` FK→`users.id`, `who_can_invite` ENUM(OWNER_ADMIN_ONLY/ALL_MEMBERS) DEFAULT OWNER_ADMIN_ONLY, `created_at`, `updated_at` | shared PK with `conversations` |
| `group_invitations` | `id` PK, `group_id` FK→`conversations.id` ON DELETE CASCADE, `invitee_user_id`/`invited_by_user_id` FK→`users.id` ON DELETE CASCADE, `status` ENUM(same 4 states), `created_at`, `responded_at`, `pending_invite_key` (VIRTUAL generated) | `UNIQUE(pending_invite_key)` = partial-unique-on-PENDING; `CHECK(invitee_user_id<>invited_by_user_id)` |

**`conversation_members` additions** (both nullable, both NULL on every existing row):
- `role` — `ENUM('OWNER','ADMIN','MEMBER') NULL DEFAULT NULL`
- `invited_by_user_id` — `BIGINT NULL DEFAULT NULL`, FK→`users.id` (no `ON DELETE` clause, matching this
  table's two pre-existing FKs), plus a supporting index `idx_convmember_invited_by`.

### Migration/schema artifact
`connectx-backend/db/migrations/V1__connection_and_group_schema.sql` — a standalone, manually-applied
SQL script. Deliberately placed outside `src/main/resources/db/migration` (Flyway's default scan path)
so it cannot be silently auto-executed if Flyway is adopted later; named in Flyway's
`V{n}__{description}.sql` convention so it can be adopted as the baseline migration with minimal
friction if that's approved in a future stage.

### Migration mechanism
**Standalone SQL script, not wired into the application** — no Flyway/Liquibase introduced (per explicit
instruction not to decide this silently), no `BeanPostProcessor`/`ApplicationRunner` hook added either
(that would itself be new application behavior, out of scope for a schema-only stage). Idempotency is
handled two ways: `CREATE TABLE IF NOT EXISTS` for the five new tables (native MySQL 8 syntax, covers
every inline index/constraint atomically); the `conversation_members` `ALTER TABLE` statements use the
standard MySQL check-`information_schema`-then-`PREPARE`/`EXECUTE` pattern, since MySQL 8 (unlike
MariaDB) has no `ADD COLUMN IF NOT EXISTS` / `ADD CONSTRAINT IF NOT EXISTS` (verified empirically — both
produce `ERROR 1064` on this server).

### Database used for verification
Both. `connectx_test_db` was used as a disposable sandbox to validate syntax, constraints, and behavior
(including a live insert/cascade-delete test) — then reset to empty, its normal steady state between
`mvn test` runs. The verified script was then applied to `connectx_db` (the real application database)
as its permanent home, and re-run a second time there to confirm idempotency.

### Verification performed
1. `SHOW CREATE TABLE` for all 5 new tables and `conversation_members` — structure matches spec exactly
   (see table above).
2. Functional tests against the sandbox: duplicate-PENDING rejection and unlimited terminal-state rows
   for the partial-unique pattern; `CHECK` constraint rejection of reversed/equal `connections` pairs and
   self-blocks; live `INSERT` + parent-row `DELETE` proving `ON DELETE CASCADE` fires correctly through a
   `VIRTUAL` generated column.
3. Ran the full script twice against both databases — second run cleanly reports "already exists,
   skipping" for every idempotent branch, zero errors.
4. Confirmed pre-existing data in `connectx_db` untouched: 83 users, 54 conversations, 1713 messages, 108
   `conversation_members` rows — identical counts before and after. All 108 existing
   `conversation_members` rows confirmed `NULL` for both new columns.
5. Confirmed all 5 new tables present and empty in `connectx_db` after applying.
6. `git status` / `git diff --stat` — only `connectx-backend/db/` (new, untracked) appeared; zero
   application files touched.

### Backend tests
`mvn test` → **BUILD SUCCESS, 25/25 tests passed, 0 failures, 0 errors** (same 9 test classes as the
Stage 0A baseline), run against a clean `connectx_test_db` bootstrap *after* confirming the new schema
does not linger there. `connectx_test_db` was confirmed empty again after the run (Hibernate's
`create-drop` completed its teardown cleanly, undisturbed by anything from this stage).

### Frontend tests/build
`npx tsc --noEmit` → clean, 0 errors. `npm run build` → succeeded (same output profile as Stage 0A
baseline — 1653 modules, identical bundle sizes, as expected since no frontend file changed).

### Existing DIRECT behavior verification
No DIRECT conversation, message, ciphertext, or nonce row was read or written by this stage. The two new
`conversation_members` columns are nullable and `NULL` on every existing row; no query anywhere in the
application references `role`, `invited_by_user_id`, or any of the 5 new tables (confirmed — no entities,
repositories, services, or controllers were added). `ddl-auto=update` does not manage unmapped tables, so
it will not alter or drop anything created here on future application boot.

### Files changed
- Added: `connectx-backend/db/migrations/V1__connection_and_group_schema.sql`
- Modified: `docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md` (this file)
- Database: `connectx_db` gained 5 new tables and 2 new nullable columns (see above). No existing table,
  row, or column altered or removed. `connectx_test_db` schema is unaffected (returns to Hibernate's
  normal empty-between-runs state).

### Known risks
- The migration script is **not yet applied anywhere except this local `connectx_db`**. Any other
  environment (a teammate's machine, staging, CI, production) needs this script run manually before
  Stage 1's entities can be expected to find the schema they map to. This is intentional (Stage 0B is
  schema-prep only, not deployment automation) but worth flagging explicitly for handoff.
- If Flyway is adopted in a later stage, this script's filename (`V1__...`) presumes it becomes the
  Flyway baseline. If a different numbering/baselining strategy is chosen instead, this file will need
  reconciling with Flyway's schema-history table at that time.
- `chat_groups` deviates from the PDF's literal table name (`groups`). Any future reading of the PDF
  alongside the actual schema needs this substitution kept in mind; noted prominently in this doc, the
  script's own header, and will be repeated in Stage 5's (group CRUD) entry when that entity is created.

### Security considerations
None — no new endpoint, query, or authorization path exists yet. The `CHECK` constraints added
(`chk_connreq_not_self`, `chk_connections_canonical_order`, `chk_user_blocks_not_self`,
`chk_groupinv_not_self`) are defense-in-depth at the DB layer for invariants the PDF's application-level
design (Stage 1+) will also enforce — belt-and-suspenders, not a substitute for the service-layer checks
still to come.

### Rollback procedure
All changes are additive; reverting the single Stage 0B commit removes the migration script from version
control but does **not** itself undo the already-applied `connectx_db` schema (SQL DDL isn't rolled back
by `git revert`). To fully roll back the database itself, manually drop the 5 new tables and the 2 new
`conversation_members` columns — since nothing reads or writes them, this is safe at any point before
Stage 1 adds entities. No existing table, row, or column needs to change either way.

### Next stage
**Stage 1 — Connection backend** (PDF Stage 1): new `connection` backend package
(entity/repository/service/controller/dto) implementing the `connection_requests`/`connections` REST
endpoints (PDF §10.1). Must map cleanly onto the schema created in this stage — no application-side DDL
drift expected, but Stage 1 should re-verify field-by-field against the live schema before writing
entities, per the master control prompt's inspect-before-implementing rule.

---

## Protected systems (do not modify without a later stage explicitly requiring it)
- Web Push encryption, RFC 8291, VAPID, push subscription, Service Worker push transport
- Authentication / JWT
- Existing DIRECT E2EE algorithm and ciphertext format
- Existing message ciphertext rows, nonce values, conversation IDs
- Existing DIRECT message delivery pipeline
- Existing notification transport
- Existing PWA behavior
- Existing WebSocket message delivery semantics (until Stage 8's explicit, approved SUBSCRIBE-auth fix)
- Secrets, environment variables, production credentials

## Stage log

| Stage | Status | Commit |
|---|---|---|
| 0A | Complete | `6afa0be` |
| 0B | Complete | `3996197` |
| 1 | Not started | — |
| 1.5 | Not started | — |
| 2 | Not started | — |
| 3 | Not started | — |
| 4 | Not started | — |
| 5 | Not started | — |
| 6 | Not started | — |
| 7 | Not started | — |
| 8 | Not started | — |
| 9 | Not started | — |
| 10 | Not started | — |
| 11 | Not started | — |
