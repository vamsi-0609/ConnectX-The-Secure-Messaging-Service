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

**Current stage: control-prompt Stage 4 (Blocking) — BACKEND ONLY, COMPLETE. Frontend NOT started.**
**Next stage: 1.5 — Connection frontend (still not started — see checkpoint below for why this is
out of stage order)**

**Checkpoint note (2026-08-17):** a later conversation referred to the current point as "Stage 3"
and requested a frontend audit (connection-request UI, pending/sent UI, accept/reject UI, blocking
UI, etc.). That frontend does not exist yet in this repo — zero files under `connectx-frontend`
reference connections or blocking, and no frontend file has changed since commit `7ae17f3` (Stage 1
backend). What has actually been built past Stage 1, in commit order, is:
- `02c3444` — DM creation enforcement (control-prompt Stage 3 per the mapping table above: "Stage 2
  enforcement half") — **backend only**.
- `00fb852` — Blocking backend foundation (control-prompt Stage 4 per the mapping table: PDF
  "Stage 3 = Blocking") — **backend only**.

Neither of these two commits was reflected in this document before now (this checkpoint adds them
retroactively from direct commit inspection, not from memory). Stage 1.5 (connection frontend) and
the frontend half of Stage 4 (blocking UI) remain **not started**. See "Checkpoint — 2026-08-17"
near the end of this document for the full verification this entry is based on.

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
Stage 1 (below).

---

## Stage 1 — Connection backend

### What was done
Inspected the existing architecture before writing anything: `UserController`/`ConversationController`
(confirmed `@AuthenticationPrincipal UserPrincipal currentUser` is the only source of "current user" ID
anywhere in the app — never trusted from a request body), `ConversationService.createOrGetDirectConversation`
(confirmed, exactly as the PDF claims, it performs no relationship check today — deliberately **not**
touched this stage; enforcement is a later, explicitly-approved stage), `SecurityConfig` (confirmed
`anyRequest().authenticated()` already covers any new `/api/v1/**` controller with zero config changes),
`CreateDirectConversationDto` (confirmed the established convention: a mutation DTO carries only the
*target* user's id, never the caller's — mirrored exactly for the new `SendConnectionRequestDto`),
`MessageService`'s reaction/star insert-race handling (the proven `REQUIRES_NEW` self-proxy +
`DataIntegrityViolationException`-catch pattern — reused verbatim for both new race-sensitive inserts),
and `MessageReaction`/`MessageStar` (confirmed the established pattern for re-declaring an
already-raw-SQL-created unique constraint on the JPA entity via `@Table(uniqueConstraints=...)`, with the
same constraint name, so `ddl-auto=update`/`create-drop` both work correctly).

Implemented the connection-request backend: entities, repositories, service, and controller for
`connection_requests` and `connections` (Stage 0B's schema — no new tables, no schema changes this
stage). `chat_groups`, `group_invitations`, and `user_blocks` remain completely unused, exactly as
required.

**Two findings surfaced during implementation, both resolved without needing to stop:**

1. **Entity naming collision risk**: a JPA entity named `Connection` would collide with the extremely
   common `java.sql.Connection` in any file that needs both. Renamed the entity to `UserConnection`
   (table name `connections` is unaffected — decoupled via `@Table(name = "connections")`, the same
   pattern already used for `chat_groups` in Stage 0B). Purely a Java class-naming choice; no schema or
   design impact.
2. **Test-database constraint parity gap**: `connectx_test_db` is rebuilt from JPA entity metadata alone
   on every test run (`ddl-auto=create-drop`), so any DB-level protection not expressible via plain JPA
   annotations — Stage 0B's partial-unique-on-PENDING generated column for `connection_requests` — would
   be silently *absent* in the test environment, even though it's present in `connectx_db`. Left
   unaddressed, the "duplicate pending request" and "concurrent request" tests would only be exercising
   the service-layer pre-check, not the real DB backstop, which is exactly the class of gap the codebase's
   own testing philosophy exists to catch (H2 was rejected for this project for the identical reason).
   Resolved two ways:
   - `UserConnection`'s full unique constraint (`uk_connections_pair`) and both CHECK constraints
     (`chk_connections_canonical_order`, `chk_connreq_not_self`) **are** expressible via
     `@Table(uniqueConstraints=...)` and Hibernate's `@Check` — added to the entities, verified empirically
     (via a temporary diagnostic test, since removed) to generate identically in `connectx_test_db` and to
     leave `connectx_db`'s existing Stage 0B constraints undisturbed.
   - The partial-unique-on-PENDING index genuinely isn't expressible via JPA annotations (it needs a
     generated column). `ConnectionRequestRaceIntegrationTest` adds the identical DDL directly via a
     `@BeforeEach` idempotent `ALTER TABLE`, so its race test exercises the same real constraint
     production has.

**A third, minor observation (not a blocker):** booting the app against `connectx_db` caused Hibernate's
`ddl-auto=update` to silently re-order `connection_requests.status`'s native MySQL `ENUM(...)` literal
list from Stage 0B's declaration order to alphabetical. Root cause: Hibernate's own DDL generation for
`@Enumerated(EnumType.STRING)` always emits alphabetically-sorted `ENUM(...)` — confirmed by
`messages.message_type`, a pre-existing column created purely by Hibernate from day one, already being
alphabetical. Harmless (the table was empty; MySQL's `ALTER ... MODIFY COLUMN` for enum reordering safely
remaps existing rows by string value even when there is data) and not something Stage 1 introduced as a
new risk — it's a pre-existing characteristic of this app's `ddl-auto=update` mechanism, now directly
observed and documented for future stages (`chat_groups.who_can_invite` will likely see the same cosmetic
reorder whenever Stage 4/5 adds its entity).

### Connection request lifecycle implemented
`PENDING → ACCEPTED` / `PENDING → REJECTED` / `PENDING → CANCELLED` (matches Stage 0B's schema and the
PDF exactly — no additional states invented). A new request after a terminal state inserts a new row,
preserving history. A pending request already existing in *either* direction between two users blocks a
new request in either direction (`409 REQUEST_ALREADY_PENDING`) — the correct next action is to respond to
the existing one, not create a redundant second row.

### Files changed (all new; nothing existing modified)
**Main** (`connectx-backend/src/main/java/com/connectx/connection/`):
`entity/ConnectionRequest.java`, `entity/ConnectionRequestStatus.java`, `entity/UserConnection.java`,
`entity/ConnectionSource.java`, `repository/ConnectionRequestRepository.java`,
`repository/UserConnectionRepository.java`, `service/ConnectionService.java`,
`controller/ConnectionController.java`, `dto/SendConnectionRequestDto.java`,
`dto/ConnectionRequestDto.java`, `dto/UserConnectionDto.java`

**Test** (`connectx-backend/src/test/java/com/connectx/connection/`):
`service/ConnectionServiceTest.java`, `service/ConnectionRequestRaceIntegrationTest.java`,
`controller/ConnectionControllerSecurityTest.java`

Zero changes to any existing file — confirmed via `git diff --stat` (empty) and `git status` (only the new
`connection` directories untracked).

### Database changes
None. Stage 0B's `connection_requests`/`connections` tables are used exactly as created; `chat_groups`,
`group_invitations`, `user_blocks` remain untouched and unused. The only DB-adjacent additions are the
entity-level `@Table(uniqueConstraints=...)` and `@Check` declarations described above, which reproduce
(never alter) constraints Stage 0B already created.

### API added
All under `/api/v1/connections`, JWT-authenticated via the existing `anyRequest().authenticated()` rule
(zero `SecurityConfig` changes needed):
- `POST /requests` — `{recipientId}` → send a request (requester always taken from the authenticated
  principal, never from the body)
- `GET /requests/pending` — my incoming PENDING requests
- `GET /requests/sent` — my outgoing PENDING requests
- `POST /requests/{id}/accept` — recipient only
- `POST /requests/{id}/reject` — recipient only
- `POST /requests/{id}/cancel` — requester only (needed so the `CANCELLED` state, already in Stage 0B's
  schema, is actually reachable)
- `GET /connections` — my accepted connections

Deliberately **not** added: any single-request-by-ID lookup endpoint. Since `/pending` and `/sent` are
always scoped to the caller's own id server-side, and no other endpoint accepts an arbitrary request ID
for *reading*, there is no IDOR surface for "view private request information belonging to unrelated
users" — closed by simply not exposing that lookup, not by an extra permission check.

### Authorization rules enforced
- Requester identity is always the authenticated caller (`UserPrincipal.getId()`), never client-supplied —
  `SendConnectionRequestDto` structurally has only `recipientId`.
- Self-request blocked (`400 SELF_REQUEST`), backed by `chk_connreq_not_self` at the DB layer too.
- Only the recipient may accept/reject (`403 FORBIDDEN` otherwise, including the requester trying to
  accept their own outgoing request).
- Only the requester may cancel (`403 FORBIDDEN` otherwise, including the recipient trying to cancel).
- A non-PENDING request cannot be accepted/rejected/cancelled again (`409 REQUEST_NOT_PENDING`).
- Already-connected pair cannot send/receive a new request (`409 ALREADY_CONNECTED`).
- Duplicate/reverse-direction PENDING request blocked (`409 REQUEST_ALREADY_PENDING`), both via a
  service-layer pre-check (clean error in the common case) and a DB-level backstop (generated-column
  partial-unique index, for the genuine race case).

### Database queries/constraints relied on
`uk_connreq_pending_pair` (Stage 0B's generated-column partial-unique index) and `uk_connections_pair`
(now also entity-declared) are the actual race backstops; the service layer's `REQUIRES_NEW` +
`DataIntegrityViolationException`-catch pattern (verbatim reuse of `MessageService`'s proven
reaction/star-race fix) converts a lost race into the same typed `409` a non-racing caller would see,
never an unhandled 500.

### Tests added — 17 total, all passing
`ConnectionServiceTest` (12): send/accept/reject/cancel lifecycle, self-request rejection, duplicate and
reverse-direction pending rejection, non-recipient accept/reject rejection, requester-attribution
(non-forgeability), already-connected rejection, and a dedicated non-interference check that exercises the
full connection-request flow alongside a pre-existing DIRECT conversation/message and asserts the
message's ciphertext/nonce/encryptionAlgorithm and the conversation's loadability are byte-for-byte
unchanged (covers test items 12–14).
`ConnectionRequestRaceIntegrationTest` (2): concurrent duplicate-pending-request race (exactly one winner,
exactly one `PENDING` row, loser gets the typed `409`) and concurrent-accept-of-the-same-request race
(never throws, exactly one `connections` row) — both against real MySQL, both confirmed to actually hit
the DB constraint (visible as expected "Duplicate entry" log lines during the run, same pattern as the
existing reaction/star race tests).
`ConnectionControllerSecurityTest` (3): unauthenticated `POST /requests`, `GET /requests/pending`, and
`POST /requests/{id}/accept` all rejected (401/403) — the one Stage 1 test needing HTTP/security-filter
coverage rather than direct service calls; introduces `@AutoConfigureMockMvc` as a new-but-standard
pattern for this codebase (no existing precedent, kept scoped to exactly this boundary).

### Existing tests result
All 25 Stage-0-baseline tests still pass unchanged. Full suite: **42/42 passing, 0 failures, 0 errors,
BUILD SUCCESS.**

### Frontend build result
No frontend file was touched (not required to keep the app compiling). `npx tsc --noEmit` clean;
`npm run build` succeeded with an identical output profile to the Stage 0 baseline (1653 modules, same
bundle sizes).

### Existing DIRECT compatibility behavior
`ConversationService.createOrGetDirectConversation` was inspected but **not modified** — it still performs
no connection check, exactly as before. Enforcement is deliberately deferred to a later, explicitly
approved stage. `ConnectionServiceTest.existingDirectConversationAndCiphertextUnaffectedByConnectionActivity`
directly verifies a pre-existing DIRECT conversation's message ciphertext/nonce/encryptionAlgorithm and
loadability are untouched while the new connection system is exercised alongside it.

### Risks discovered
- None blocking. The enum-reordering observation and the `java.sql.Connection` naming collision (both
  above) were found and resolved within this stage; neither required stopping per the fail-safe rule since
  neither touched E2EE, Web Push, PWA, message encryption, existing message delivery, existing DIRECT data,
  the migration mechanism, or authentication architecture.
- `ConnectionControllerSecurityTest` introduces `@AutoConfigureMockMvc`, a testing pattern with no prior
  precedent in this codebase. Scoped tightly to the one boundary that genuinely needs it; flagged here so a
  future stage doesn't mistake it for an established convention to expand casually.

### Rollback procedure
Delete the `connectx-backend/src/main/java/com/connectx/connection/` and
`connectx-backend/src/test/java/com/connectx/connection/` directories and revert the Stage 1 commit. No
schema, existing entity, existing endpoint, or existing test was modified, so no other cleanup is needed.

### Next stage
**Stage 1.5 — Connection frontend** (PDF Stage 1, frontend half): `connectionApi.ts`, a
`ConnectionRequestsPanel.tsx`-equivalent, and gating `UserSearchModal.tsx`'s "Start Chat" action on
connection status (PDF §12.2) — but per the master control prompt, DM enforcement itself
(`connectx.connections.enforce`-style gating of `createOrGetDirectConversation`) is a separate, later
stage (control-prompt Stage 3), not bundled into the frontend work.

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
| 1 | Complete | `7ae17f3` |
| 1.5 | Not started (frontend) | — |
| 2 | Not started — no backfill script exists (`grep -r backfill` across the repo returns nothing). `02c3444` did not backfill `connections` rows for pre-existing DIRECT pairs; instead it exempts any conversation that already exists from the new connection check, which is a different mechanism than the PDF's Stage 2 backfill. Flag for a later stage to confirm this substitution is intentional and permanent rather than a gap. | — |
| 3 | Complete — backend only (DM creation now requires an accepted connection for *new* pairs; pre-existing pairs exempted per above) | `02c3444` |
| 4 | Backend only, complete (blocking foundation + enforcement at DM-create, connection-request, and message-send boundaries). Frontend not started. | `00fb852` |
| 5 | Not started | — |
| 6 | Not started | — |
| 7 | Not started | — |
| 8 | Not started | — |
| 9 | Not started | — |
| 10 | Not started | — |
| 11 | Not started | — |

---

## Checkpoint — 2026-08-17

Requested as a pause-and-audit before continuing further work. No implementation performed this
checkpoint — verification only, and this document update.

**Git state:** branch `feature/pwa-notifications`, HEAD `00fb852`, working tree clean, 8 commits
ahead of `origin/feature/pwa-notifications` (not pushed). All Connections/Blocking work to date is
already committed — nothing was uncommitted at checkpoint time, so no new commit was made.

**Frontend for connections/blocking: does not exist.** `git ls-files -- connectx-frontend | grep -iE
"(ConnectionRequest|Relationship|BlockUser|blockApi|connectionApi|PendingRequest)"` returns nothing,
and `git diff 7ae17f3..HEAD --stat -- connectx-frontend` is empty. Every item in a "Stage 3 frontend"
checklist (connection-request UI, pending/sent-request UI, accept/reject UI, blocking/unblock UI,
relationship-state handling, search gating, loading/error states, duplicate-click protection) is
**not implemented** — there is no frontend code to audit. `UserSearchModal.tsx`'s "Start Chat" action
still calls `POST /api/v1/conversations/direct` unconditionally; a `403 NOT_CONNECTED` or `403
BLOCKED` from the backend would currently surface as whatever this app's generic API-error handling
does, not a purpose-built UI state (not verified further since building/wiring that is exactly the
still-pending Stage 1.5 work).

**Build verification:**
- `npx tsc --noEmit` (connectx-frontend) — clean, 0 errors.
- `npm run build` (connectx-frontend) — succeeded, 1653 modules, same profile as prior checkpoints.
- `mvn test` (connectx-backend) — **BUILD SUCCESS, 77/77 tests passed, 0 failures, 0 errors.** The
  `SqlExceptionHelper` "Duplicate entry" ERROR-level log lines during `ReactionRaceIntegrationTest`
  and `StarRaceIntegrationTest` are expected, caught-and-handled race-test noise (same pre-existing
  pattern noted in Stage 0A), not failures.

**Protected systems — verified untouched by both post-Stage-1 commits (`02c3444`, `00fb852`), by
direct diff inspection, not assumption:**
`WebPushService.java`, `WebPushPayloadEncryptor.java`, VAPID/push-subscription code,
`PresenceService.java`, `WebSocketMessageController.java`, `PushNotificationController.java`, every
`sw.js`/`browserNotifications.ts`/`serviceWorker.ts`-equivalent, E2EE encryption/decryption,
identity-key handling, message ciphertext/persistence, database schema/Flyway, and every
`chat_groups`/`group_invitations`/Groups file are all untouched. The two commits touched exactly:
`ConversationService.java`, `MessageService.java` (only an early authorization check inserted before
the existing persistence/broadcast logic — the encryption, persistence, and WebSocket-broadcast code
paths themselves are unmodified), `UserRepository.java`, `ConversationMemberRepository.java`,
`ConnectionService.java`, and the new `block/` package plus tests.

**API contract:** `BlockController` (`POST/DELETE /api/v1/blocks/{userId}`, `GET /api/v1/blocks`) and
the Stage 1 `ConnectionController` endpoints match the expected contract exactly. Blocker/requester
identity is always `@AuthenticationPrincipal UserPrincipal currentUser`, never a client-supplied
`blockerId`/`requesterId` — confirmed by reading both controllers directly.

**Performance / N+1:** not assessable — there is no frontend caller yet, so the relationship-API
call counts requested (per search result, per send/accept/reject/block/unblock) are currently zero
by construction. Revisit this once Stage 1.5 wires the frontend up.

**Existing DIRECT compatibility:** confirmed by direct code reading of `02c3444`'s diff — the
existing-conversation branch of `createOrGetDirectConversation` returns before both the block check
and the `NOT_CONNECTED` check are reached, so a pre-existing DIRECT conversation with no `connections`
row keeps working unconditionally, exactly as `DirectConversationAuthorizationTest` (added in that
commit) asserts.

**Manual test checklist (pending — none of these are testable yet without the Stage 1.5/Stage-4-
frontend work; listed for later use):**
- A. Search unconnected user
- B. Send request
- C. Cancel request
- D. Receive request
- E. Accept
- F. Reject
- G. Connected → message
- H. Existing legacy DIRECT chat still opens
- I. Block
- J. Unblock
- K. Reload persistence
- L. Backend `403 BLOCKED` surfaces without an app crash
- M. Backend `403 NOT_CONNECTED` surfaces without an app crash

**Known risks / follow-ups:**
1. Stage numbering drift: this repo's own control-prompt mapping (top of this document) puts DM
   enforcement at Stage 3 and blocking at Stage 4 — both already done, backend-only. Frontend for
   connections (Stage 1.5) has not been started at all. Whoever resumes this work should treat the
   next stage as **Stage 1.5 (connection frontend)**, not "Stage 3.5" or "Stage 4" frontend, and
   should decide whether blocking frontend gets bundled into that same pass or its own stage.
2. Control-prompt Stage 2 (DIRECT-conversation backfill into `connections`) was never done as
   specified — see the Stage log row above. The enforcement commit substituted an exemption
   mechanism instead. This works today but means `connections` will never contain rows for
   pre-Stage-1 DIRECT pairs; flag if any future feature (e.g., "show mutual connections",
   connection-list completeness) assumes `connections` is authoritative for all DIRECT
   relationships.
3. This document had silently fallen behind two real commits (`02c3444`, `00fb852`) before this
   checkpoint — neither had a corresponding state-doc entry or Stage-log row. Backfilled from direct
   `git show`/`git diff` inspection this checkpoint, not from memory of having done the work.

**No code was changed this checkpoint.** Working tree was clean before and after; this document edit
is the only change made.
