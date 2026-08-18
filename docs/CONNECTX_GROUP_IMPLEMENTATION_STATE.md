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

**Current stage (as of 2026-08-18, HEAD `86c7d48`): control-prompt Stages 1.5 through 4 are now
COMPLETE, backend AND frontend — connections, DIRECT-conversation enforcement, and blocking all have
working, verified UI. Several substantial features outside the PDF's numbered stages were also built
in parallel (PWA WebSocket resume/recovery, a Settings screen with profile-photo visibility, and
client-side chat export) — see "Checkpoint — Final Pre-Groups Stability Check (2026-08-18)" at the
end of this document for the full audit this status is based on.**
**Next stage: GROUPS ARCHITECTURE REVIEW (control-prompt Stage 5 / PDF Stage 4) — not started.**

Everything below this point through "Checkpoint — Legacy DIRECT connection backfill (2026-08-18)" is
the original per-stage record and is left as-is (historical, not rewritten). The **"Current status"
block above supersedes the stale "BACKEND ONLY" / "not started" claims that follow** — those were
accurate as of the 2026-08-17 checkpoint but not after `5a96cac` ("add connection and blocking
frontend") and the many commits since. See the final checkpoint section for the up-to-date picture.

**Checkpoint note (2026-08-17, historical):** a later conversation referred to the current point as "Stage 3"
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
| 1.5 | Complete (connection + blocking frontend) | `5a96cac`, refined through `86c7d48` — see final checkpoint below |
| 2 | **Data backfill script exists and has been run against the local dev `connectx_db`** (see "Legacy DIRECT connection backfill" checkpoint below) — this is the PDF's Stage 2 backfill, executed as an explicit, reviewed, one-time data operation, not application logic. The Stage 3 (`02c3444`) exemption mechanism is untouched and remains the reason old conversations keep working regardless of `connections` row presence; the two are complementary, not one replacing the other. | — (data-only; no code commit) |
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

---

## Checkpoint — Legacy DIRECT connection backfill (2026-08-18)

Executes the PDF's Stage 2 backfill, left undone since the checkpoint above flagged it. Explicit,
reviewed, one-time **data** operation against the local development `connectx_db` — no application
code changed.

**Source-value decision:** the task instructions asked for a new `LEGACY_BACKFILL` `ConnectionSource`
value; inspection found `ConnectionSource` already has an unused `MIGRATED` value, present in both the
Java enum and this table's native `ENUM('REQUEST','MIGRATED')` column since Stage 0B (`V1__...sql`),
referenced nowhere in any code path. Stopped and asked before proceeding, per the instruction not to
silently reuse `REQUEST` or silently modify the enum; user chose to use the existing `MIGRATED` value
instead of adding a new one. Net effect: **zero schema or Java enum changes** — this backfill is pure
data (a single `INSERT IGNORE ... SELECT` against the already-provisioned `connections` table).

**What `MIGRATED` means here — read before ever pointing this script at non-test data:** a
`connections` row with `source = MIGRATED` means *"these two users already had a DIRECT conversation
before the connection-request system existed, and this development/test database's data was migrated
into the new connection model for consistency."* It is **not** evidence that either user ever sent or
accepted a connection request — historical DIRECT conversations could be (and were) created by one
user unilaterally searching and clicking a result, with no acceptance step, back before Stage 1.5
added the search-modal gating. A future feature that treats `connections` rows as proof of mutual
consent (e.g. "mutual connections", any compliance/audit surface) must account for this distinction,
or must exclude `MIGRATED` rows. Do not re-run this script against a database containing real user
relationships without re-confirming this caveat still holds and is acceptable for that use case.

**Artifact:** `connectx-backend/db/migrations/V2__backfill_legacy_direct_connections.sql` — standalone,
same convention as V1 (not Flyway, not wired into application startup). Applied manually:
`mysql -u root -p connectx_db < db/migrations/V2__backfill_legacy_direct_connections.sql`.

**Results (local `connectx_db`, verified by direct read-only SQL before and after):**
- Before: 84 users, 56 DIRECT conversations (0 GROUP), 56 unique DIRECT user-pairs, 3 `connections`
  rows (all `source=REQUEST`), 53 DIRECT pairs with no `connections` row.
- After: 56 `connections` rows total — the original 3 `REQUEST` rows byte-for-byte unchanged (same
  `id`/`user_id_low`/`user_id_high`/`source`/`created_at`), plus 53 new `MIGRATED` rows, one per
  previously-missing pair. 0 self-connections, 0 duplicate pairs, 0 GROUP-sourced rows, 0 DIRECT pairs
  still missing a connection.
- Idempotency verified empirically: ran the script a second time, inserted 0 additional rows, the 3
  `REQUEST` rows remained identical.
- `conversations`/`conversation_members`/`messages`/`users`/`connection_requests`/`user_blocks` row
  counts identical before and after (56/112/1740/84/3/0); a message-content fingerprint
  (`MD5` over id+ciphertext+nonce+encryption_algorithm for all 1740 rows) was recorded post-backfill
  for future tamper-evidence, though the script's SQL text makes it structurally impossible for it to
  have touched `messages` in the first place (it contains exactly one `INSERT` statement, targeting
  `connections` only).

**Application logic unchanged:** `getRelationshipStatus()`, `ConversationService`, `UserSearchModal`,
`ContactInfoDrawer`, `ConnectionService.removeConnection()` were not modified. `GET /api/v1/connections`
now simply returns more rows for users who were backfilled — the frontend's existing Stage 3 relationship
derivation and Remove Connection flow apply to those rows exactly as they already do for `REQUEST` rows,
with no code change required.

**Regression:** `mvn test` — 86/86 passing (unchanged; no backend code touched). Frontend
`npx tsc --noEmit` clean, `npm run build` produced byte-identical asset hashes to the pre-backfill
build (confirms zero frontend source changes).

**No application code was changed this checkpoint.** Only `connectx-backend/db/migrations/V2__...sql`
(new) and this document were added/edited; `connectx_db` gained 53 new `connections` rows via the
script above. `connectx_test_db` was not touched (it is rebuilt fresh by `ddl-auto=create-drop` on
every `mvn test` run and never depends on this script).

---

## Checkpoint — Final Pre-Groups Stability Check (2026-08-18)

READ-ONLY audit requested as the final gate before Groups. No application behavior was modified —
verification only, plus this document update. Performed via two parallel deep-inspection passes
(Connections/DIRECT/Blocking; PWA-WebSocket/Settings/Chat-export/Web-Push) covering every file each
area touches, plus direct DB inspection and a fresh `mvn test`/`tsc`/`vite build` run.

**Git state:** branch `feature/pwa-notifications`, HEAD `86c7d48`, working tree clean, 2 commits
ahead of `origin/feature/pwa-notifications` (`8d3e880`, `86c7d48` — not pushed). Everything back
through Stage 0A (`6afa0be`) is confirmed a real, linear ancestor of HEAD (`git merge-base
--is-ancestor` verified) — a prior read of this document momentarily looked like it referenced
fabricated commits because they're older than a 15-commit `git log` window, not because they don't
exist.

**Build baseline:**
- Backend: `mvn test` → **BUILD SUCCESS, 112/112 tests passed, 0 failures, 0 errors** (across 13 test
  classes, including the 9 new profile-visibility-update tests added this session). The recurring
  `SqlExceptionHelper` "Duplicate entry" ERROR-level log lines during `ReactionRaceIntegrationTest`/
  `StarRaceIntegrationTest` are expected, caught-and-handled race-test noise, not failures — same
  pre-existing pattern noted in every prior checkpoint.
- Frontend: `npx tsc --noEmit` → clean, 0 errors. `npm run build` → succeeded (1663 modules
  transformed).

**Features verified implemented and working (backend + frontend, all confirmed by direct code
reading this checkpoint, not by trusting prior memory):**
- Connection requests: send/accept/reject/cancel, self-request/duplicate-pending/already-connected
  rejection, block-precedence, race-safety (`REQUIRES_NEW` + constraint backstop).
- DIRECT conversation authorization: new conversations require an active connection; pre-existing
  (legacy/backfilled) conversations remain openable without one; sending a *new* message requires a
  currently-active connection, not just membership; a block created after a request was sent still
  blocks acceptance.
- Blocking: block/unblock scoped to the authenticated caller only, `existsEitherDirection` enforced
  at every relationship boundary (connections, conversations, messages), blocking terminates any
  existing connection + cancels pending requests, blocked-list never leaks "who blocked me", search
  excludes blocked pairs in either direction at the DB query level.
- PWA/WebSocket resume: `WebSocketClient.connect()`'s guard uses the class's own `status` field
  (not the previously-buggy `stompClient.active`); `visibilitychange`/`focus`/`online` all funnel
  through one no-op-when-connected resume check; bounded backoff (1s→30s cap); no duplicate-socket
  path found under simultaneous-event firing (traced synchronously — JS single-threadedness plus the
  synchronous `status` flip make this safe).
- Settings / Profile Photo Visibility: EVERYONE/CONNECTIONS enforced server-side at every DTO
  composition site (batched to avoid N+1) *and* at the raw image-serving endpoint itself (not just
  the JSON); NULL (pre-existing users) correctly treated as EVERYONE everywhere.
- Chat export: 100% client-side generation, zero plaintext ever sent to the backend, reuses the
  existing `before`/`limit` cursor pagination (no N+1), only the four real `MessageType` values are
  handled.
- Web Push / PWA notifications: untouched by every commit since `83d69de` — stable, not modified by
  any of the connections/blocking/PWA-recovery/Settings work audited above.

**Security audit — two findings, both READ-ONLY reported per instruction, NEITHER fixed:**

1. **CONFIRMED BUG — user email leaked to any authenticated caller, unrelated to blocking/connection
   status.** `UserDto.fromEntity(User)` (`connectx-backend/src/main/java/com/connectx/user/dto/
   UserDto.java:20-32`) unconditionally sets `email`, and the viewer-aware 2-arg overload
   (`:40-46`) only nulls `profileImageUrl` — `email` is never gated. This means `GET /api/v1/
   users/{id}`, `GET /api/v1/users/search`, and every `ConversationMemberDto` (i.e. every
   conversation's member list, `GET /api/v1/conversations`) hand any authenticated caller the
   target's email address regardless of block status, connection status, or any relationship at
   all. Contradicts the stated design intent in sibling DTOs' own comments (`ConnectionRequestDto
   .java:8-11`, `UserBlockDto.java:8-9`: "should not leak more than discovery already does") — but
   the "discovery" baseline (`/users/search`) is itself leaky, so that invariant is satisfied
   against an already-broken baseline. **Directly relevant to Groups**: a group member-list UI will
   almost certainly reuse `ConversationMemberDto`/`UserDto` as-is, which would scale this from "any
   two users who've searched or shared a DIRECT chat" to "every member of every group sees every
   other member's email." Recommend fixing before Groups ships a member-list or invite-search
   surface built on these DTOs.

2. **CONCERNING — latent JWT-exfiltration vector via unvalidated `profileImageUrl`.**
   `PATCH /api/v1/users/me`'s `profileImageUrl` field (`UserProfileUpdateDto.java`) has no
   server-side validation restricting it to the internal `/api/v1/profile-images/...` path —
   `UserService.updateUserProfile` sets whatever string is supplied directly onto the entity. This
   was harmless before this session's PWA/Settings work; it became a real exfiltration vector
   specifically because of a change **I made in this session** (commit `8d3e880`):
   `connectx-frontend/src/utils/profileImage.ts`'s `withAuthToken()` now appends the viewer's live
   JWT (`?token=...`) to *any* `http://`/`https://` URL passed through `resolveProfileImageUrl`,
   with no check that the URL's origin matches `config.apiBaseUrl` (`profileImage.ts:25-26`). The
   current shipped UI never sets an external URL through this field (photo upload always derives an
   internal path), so there is no exploit through the web client as shipped today — but any user
   could call `PATCH /users/me` directly with `{"profileImageUrl":"https://attacker.example/x.png"}`,
   and every other user whose browser subsequently renders that profile's avatar (`<img>` tag) would
   leak their own JWT to the attacker's server via the request. **Recommend fixing before Groups**
   (which will reuse this exact avatar-resolution path for group photos/member avatars, widening
   exposure) via either origin-restricting `withAuthToken` client-side, or validating
   `profileImageUrl` server-side to only accept the internal storage path pattern — ideally both.

**Other audit notes (not bugs, flagged for Groups implementers' awareness):**
- `connection_requests.pending_pair_key` (and `group_invitations.pending_invite_key`, same
  generated-column shape) is *not* itself direction-symmetric at the DB level — the app-level
  dual-direction pre-check is what actually prevents A→B + B→A pending duplicates; the DB constraint
  alone only stops an exact-direction race. Currently fine (the app-level check is never bypassed),
  but worth remembering since `group_invitations` was built with the identical pattern and currently
  has zero application code exercising it.
- `BlockService`'s idempotent re-block path skips re-running `terminateExistingConnectionAndPendingRequest`,
  justified by "no code path can currently create a new connection once a block exists." This holds
  today (connections are only created via `acceptRequest` or the one-time backfill script, both of
  which check blocks first) but would need re-examination if Groups introduces any new path that
  creates a `UserConnection` row (e.g. an "auto-connect group members" feature, if ever proposed).
- `MessageService.editMessage` checks only `senderUserId == currentUserId`, with no independent
  conversation-membership re-check — safe today only because there is no "leave/remove member from
  conversation" feature yet. This becomes a real gap the moment Groups adds member removal/leave: an
  ex-member could still edit their own historic messages in a group they're no longer part of.
- Blocked-pair search exclusion (`UserRepository.searchByUsernameExcludingBlockedPairs`) hand-
  duplicates the either-direction OR condition in its own JPQL rather than reusing
  `UserBlockRepository.existsEitherDirection` — functionally correct today, but two independent
  implementations of the same invariant is a sync-drift risk if blocking semantics ever change.

**Database audit:** `connectx_db` — 87 users, 62 conversations (all `DIRECT`, 0 `GROUP`), 124
`conversation_members`, 2012 messages, 60 `connections`, 17 `connection_requests`, 0 `user_blocks`
(dev data, not indicative of feature health — blocking is exercised and tested, just not currently
used on this dev DB). `chat_groups` and `group_invitations` tables exist (from Stage 0B's schema
prep) with **0 rows each**, and a repo-wide grep of `connectx-backend/src/main/java` confirms **zero**
Java files reference either table/entity name — confirmed still completely unused by application
code, exactly as required for this checkpoint. `ConversationType` enum already contains `GROUP`
(unused). `conversation_members.role`/`invited_by_user_id` columns exist (nullable, NULL on every
current row), also unreferenced by any current entity/repository/service.

**Protected systems — confirmed stable via `git log` on each path:**
- E2EE crypto (`connectx-frontend/src/crypto/`) and `MessageService.java` — last touched `7e4ec72`
  (connection-authorization enforcement, a legitimate, already-reviewed change), nothing since.
- Web Push / service worker (`connectx-backend/.../push/`, `connectx-frontend/public/sw.js`) — last
  touched `83d69de`, well before all connections/blocking/PWA-recovery/Settings work; confirmed
  untouched by every commit audited this checkpoint.
- WebSocket/STOMP (`connectx-frontend/src/websocket/`, `WebSocketConfig.java`,
  `WebSocketAuthChannelInterceptor.java`) — last touched `4619626` (the in-scope PWA reconnect fix),
  nothing since.
- Authentication/JWT (`JwtAuthenticationFilter`, `JwtTokenProvider`, `SecurityConfig`) — not modified
  by any commit in the audited range; the profile-image auth fix (finding above aside) reused the
  filter's pre-existing `?token=` fallback rather than changing auth architecture.

**Existing DIRECT data integrity:** unchanged this checkpoint (read-only). Prior checkpoints already
verified byte-identical ciphertext/nonce/message counts across every schema/backfill change; nothing
in this checkpoint touched the database.

**Group readiness assessment** (based solely on what's verified in this repo — no invented
implementation details):

- *Reusable as-is*: `Conversation`/`ConversationMember` entities (already `ConversationType`-typed,
  `GROUP` value already exists unused), the `chat_groups`/`group_invitations` schema (provisioned,
  untouched, ready for entity mapping), `conversation_members.role`/`invited_by_user_id` columns
  (provisioned, unused), message persistence/ciphertext model (keyed by `conversation_id`, not
  DIRECT-specific), reaction/star/pin mechanics (conversation-agnostic), the STOMP topic-per-
  conversation pattern (`/topic/conversation/{id}`, already id-based not DIRECT-specific).
- *Must remain DIRECT-only*: `ConversationService.createOrGetDirectConversation`'s two-party
  connection-required semantics (group creation needs its own flow against `chat_groups`, not an
  extension of this method); the current 1:1 framing of `ProfileVisibilityService`
  (EVERYONE/CONNECTIONS is defined in terms of exactly one other user — whether/how group membership
  should interact with photo visibility is an open design question, not yet decided anywhere in this
  repo).
- *Must become conversation-type-aware*: `MessageService.sendMessage`'s DIRECT-specific
  connection/block authorization block needs a parallel GROUP branch (membership + role-based send
  permission); the frontend's `getOtherParticipant`-style "exactly one other member" framing used
  throughout chat UI needs multi-member handling for group views; chat export is currently
  explicitly DIRECT-gated and would need an explicit decision (extend vs. keep DIRECT-only) for
  groups.
- *Must be added — membership*: group membership CRUD (add/remove/role-change) against the existing
  `conversation_members.role` column; a `chat_groups` entity/repository/service (table exists, zero
  code references it); a `group_invitations` entity/repository/service (same — table exists, unused).
- *Must be added — authorization*: role-based permission checks (`who_can_invite` policy already in
  schema, no service logic yet); **WebSocket SUBSCRIBE-time authorization** — flagged as a known,
  already-documented gap in this same document's Stage 8 entry and not re-verified fresh this
  checkpoint (out of scope to touch, per protected-systems instruction), but load-bearing for Groups
  specifically: DIRECT's implicit 2-party model makes an unauthenticated SUBSCRIBE lower-risk than it
  will be once a GROUP topic could be subscribed to by any authenticated user who knows/guesses the
  conversation id.
- *Must be added — encryption*: **the single largest open architectural question.** Current E2EE is
  strictly pairwise (ECDH between two parties' device keys); this repo has made no decision yet
  between per-recipient encryption fan-out vs. a shared-group-key scheme. This document's own
  PDF-stage mapping already treats group crypto as a "decoupled design spike" (PDF Stage 8) — that
  remains true; nothing this checkpoint found changes or resolves it.
- *Must be added — notifications*: Web Push fan-out to N group members (currently DIRECT/single-
  recipient in shape) respecting each member's existing per-conversation mute state
  (`conversation_members.muted_until`, already exists and reusable) — not audited deeply this
  checkpoint since Web Push is a protected system; flagged as unverified-for-multi-recipient, not
  confirmed broken.
- *Must be added — UI*: group creation, member list/management, role indicators, invitation flow,
  group settings (name/description/avatar/who-can-invite) — none of this exists in the frontend
  today.
- *Architectural risks before implementation, in rough priority order*: (1) E2EE group-encryption
  scheme is undecided — blocks any real group *messaging* work regardless of how much scaffolding
  gets built first; (2) WebSocket SUBSCRIBE-time auth gap must close before any group topic exists;
  (3) the two security findings above (email leak, avatar-URL JWT exfiltration) will scale in
  exposure the moment Groups reuses `UserDto`/`ConversationMemberDto`/avatar-resolution as-is for
  member lists and group avatars — fixing both before building those surfaces is cheaper than
  fixing them after; (4) `editMessage`'s missing membership re-check needs revisiting once
  leave/remove-member exists; (5) the group membership/role schema has sat untouched since Stage 0B
  and has never been exercised by real entity code — expect the same kind of minor friction Stage 1
  hit when connection-schema entities were first wired up (e.g. Hibernate's alphabetical `ENUM`
  reordering), not a reason to delay, just don't assume zero-friction.

**Files changed this checkpoint:** `docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md` only (this
document — status/stage-log corrections plus this section). No application source file was modified.

**Next stage:** GROUPS ARCHITECTURE REVIEW. Do not begin implementation until that review explicitly
starts.
