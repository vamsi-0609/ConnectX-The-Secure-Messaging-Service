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

**Current stage: 0A — Git/checkpoint/baseline verification — COMPLETE**
**Next stage: 0B — Database/schema preparation only**

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
Recorded in the Stage 0B entry of this document once that stage's commit lands (see `git log` for the
commit whose message begins `chore(connectx): Stage 0A baseline checkpoint`).

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
**Stage 0B — Database/schema preparation ONLY** (PDF Stage 0): add `connection_requests`,
`connections`, `user_blocks`, `groups`, `group_invitations` tables and two nullable, additive columns on
`conversation_members` (`role`, `invited_by_user_id`). Purely additive — no application code will read
or write the new schema yet. Per PDF §5.3, evaluate introducing Flyway at this stage (vs. the guarded
`ApplicationRunner` fallback) before writing migration DDL.

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
| 0A | Complete | (this commit — see `git log`) |
| 0B | Not started | — |
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
