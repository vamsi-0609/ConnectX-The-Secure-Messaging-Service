# ConnectX Group Architecture — Design Document

**Status:** DRAFT — architecture review only. No implementation has begun. This document is the authoritative Groups architecture source of truth once approved.

**Base commit:** `d69edb3` (branch `feature/pwa-notifications`, pushed, working tree clean at time of writing).

**How to read this document:** every section states what's reusable from the existing codebase (with file:line evidence), then a recommendation. Six items are explicitly called out as **REQUIRES YOUR APPROVAL** — do not treat this document as final until those are resolved. Everything else is a considered recommendation, not yet implemented, open to pushback but not blocking.

---

## 1. Goals

Support secure group messaging in ConnectX: owner/admin/member roles, dynamic per-group permissions, connection-aware invitations, member management, group settings, a dedicated group contact-info UI, notifications, and a foundation that doesn't preclude join requests, invite links, and WebRTC group calls later — without degrading or duplicating the existing DIRECT messaging, E2EE, WebSocket, Web Push, connection, or blocking systems.

## 2. Non-goals (this document, and V1)

Not designing or implementing in this pass: WebRTC calls (design compatibility only, Step 20), MLS/Sender-Keys crypto, invite links, join requests, per-user "who can add me to groups" enforcement (setting is designed, enforcement deferred), mention parsing, message-type-specific group permissions beyond the three V1 settings. See §31/§32 for the full V1/Future split.

---

## 3. Existing architecture reused

Verified by direct inspection (backend and frontend agents, plus direct DB queries), not assumed:

| Reused as-is | Evidence |
|---|---|
| `Conversation`/`ConversationType` entity — already `DIRECT\|GROUP` typed | `conversation/entity/Conversation.java`, `ConversationType.java` |
| `ConversationMember` per-user-per-conversation state (pin/mute/archive/mark-read/soft-delete) | `ConversationMember.java` — every field already generic, no 2-member assumption |
| `ConversationMemberRepository`'s 12 query methods | All generic; only `findMemberUserIdsExcluding` is DIRECT-*invoked* (not DIRECT-*coded*) |
| `ConversationService`'s 13 of 14 methods (pin/unpin/mute/archive/delete-for-user/mark-read/etc.) | Every method except `createOrGetDirectConversation` operates on one caller's `ConversationMember` row, already type-agnostic |
| `messages` table / `Message` entity — single ciphertext/nonce per row | Confirmed no per-recipient structure exists; this is exactly what a shared-group-key design needs (§14) |
| Typed-exception convention (`ApiException(status, code, message)` + `GlobalExceptionHandler`) | Used everywhere; Groups follows the same convention |
| `REQUIRES_NEW` self-proxy + `DataIntegrityViolationException`-catch race pattern | `MessageService.starMessage`/`insertStarInNewTransaction` (full example in §24), reused verbatim by `ConnectionService`, `BlockService` |
| `Isolation.READ_COMMITTED` override pattern for cross-transaction visibility gaps | `createOrGetDirectConversation`, `addOrUpdateReaction`, `blockUser` — same documented rationale each time |
| Partial-unique-on-PENDING via `VIRTUAL` generated column | `connection_requests.pending_pair_key`, already replicated in `group_invitations.pending_invite_key` |
| Push notification fan-out — already per-member, already generic | `MessageService.sendMessage` loops `allMembers`, calls `webPushService.sendPushToUserAsync` once per member, skipping sender and muted members — **already N-member-safe, no redesign needed** |
| Pairwise ECDH-P256 + AES-256-GCM primitives (`encryptMessage`/`decryptMessage`) | Reused *for key wrapping*, not for message bodies, in the recommended E2EE design (§14) |
| Per-user (not per-conversation) keying in `conversationCache`'s public-key map, and in `crypto/storage.ts` | Already generic — looking up N members' keys is N calls to an existing method, not a schema change |
| `ProfileVisibilityService` (block-aware photo visibility) | Reused as-is for group member avatars — see §11 |
| `chat_groups` table (name/description/avatar_url/created_by/who_can_invite) | Exists, zero Java references today — needs an entity, not a redesign |
| `group_invitations` table (PENDING/ACCEPTED/REJECTED/CANCELLED) | Exists, zero Java references today — needs an entity, not a redesign |
| `conversation_members.role`/`invited_by_user_id` columns | Exist in DB, **unmapped by the JPA entity** — needs mapping, not new columns |

**Critical structural finding that simplifies the WebSocket design:** `MessageService.sendMessage` already sends every message twice on the backend — once as a broadcast to `/topic/conversation/{id}` (needs a *subscription*) and once individually to each member's `/user/queue/messages` (needs only the CONNECT-time authenticated session, already fanned in via `convertAndSendToUser`, no topic subscription required). This means **message delivery to a group's members already does not depend on `/topic/conversation/{id}` subscription at all** — the single-active-topic-subscription design in `WebSocketClient.ts` (confirmed: exactly one `/topic/conversation/{id}` subscription at a time, switched on conversation-open) is sufficient for delivery. It's only needed for *live, in-view* features scoped to the currently-open chat (today: typing indicators). **No WebSocket client redesign is required for group message delivery.** This removes what looked like a major frontend risk.

---

## 4. DIRECT vs GROUP model

**Conceptual model, verified against the code, holds:**

```
Conversation
├── DIRECT  → exactly 2 ConversationMember rows, created only via createOrGetDirectConversation
└── GROUP   → 2+ ConversationMember rows, created only via a new createGroup path
```

**Must remain DIRECT-only** (do not generalize, do not touch):
- `ConversationService.createOrGetDirectConversation` — its self-chat/connection/block pairwise logic is inherently 2-party.
- `utils/relationship.ts`'s `LEGACY_CHAT` fallback and `findExistingDirectConversation` — both explicitly `type === 'DIRECT'`-filtered; this is correct and should stay filtered, not extended.
- Chat export (`chatExport.ts`) — explicitly documented as acting on "the currently open DIRECT conversation's other participant." Stays DIRECT-only for V1 (§32).

**Must become conversation-type-aware** (the actual surface area of this feature):
- `MessageService.sendMessage`'s authorization block — already has an `if (type == DIRECT) {...}` branch with a comment stating group authorization was deliberately deferred ("group send is authorized by membership, not by every pairwise relationship among members... group authorization is a separate, not-yet-built concern"). This is a clean, already-anticipated extension point (§12).
- **Every frontend call site of `getOtherParticipant`** (`App.tsx:83-87`, a `.find()` returning the *first* non-self member) — used in decrypt resolution, send-path peer resolution, `ChatListSidebar.getRecipient`, `ChatHeader`, `ContactInfoDrawer`, `NotificationToast`'s sender-name resolution. This is the single biggest frontend risk: for a 3+-member conversation it silently returns one arbitrary member and renders the whole UI as if it were a 1:1 chat. Every one of these call sites needs an explicit `type === 'GROUP'` branch, not a generalized "other participant" concept (a group has no single "other participant").
- WebSocket SUBSCRIBE authorization — currently doesn't exist for *any* conversation type (§13); fixing it is a prerequisite for groups but also closes a real gap for DIRECT today.

**Where a separate service is cleaner:** a new `GroupService` (creation, settings, invitations, membership CRUD, role changes) rather than extending `ConversationService` — matches the existing pattern where `ConnectionService`/`BlockService` are already separate from `ConversationService` despite being conversation-adjacent. `ConversationService` stays the "generic per-member-row conversation state" service; `GroupService` owns everything GROUP-specific (settings, roles, invitations) the same way `ConnectionService` owns connection state without living inside `UserService`.

---

## 5. Group data model

| Entity | Needed for V1? | Why |
|---|---|---|
| `ChatGroup` (maps `chat_groups`) | **Yes** | Table exists; needs a JPA entity. No schema redesign — just add 2 columns (§21). |
| `ConversationMember.role` / `.invitedByUserId` mapping | **Yes** | Columns exist, unmapped. Map them; no schema change. |
| `GroupInvitation` (maps `group_invitations`) | **Yes** | Table exists, correct shape for V1's invite-only flow. |
| `GroupJoinRequest` | **No — Future** | No table exists. Only needed once a self-service join flow (join requests / invite links) ships. Designed in §9 for completeness, not built in V1. |
| `GroupSettings` as a separate entity | **No** | 3 small ENUM columns directly on `chat_groups` (§5.1) are sufficient and consistent with how `who_can_invite` was already added — a separate settings entity/table would be premature generalization for 3 fields. |
| `GroupRole` as a separate entity | **No** | Already a native MySQL `ENUM('OWNER','ADMIN','MEMBER')` on `conversation_members.role` — a full entity would be over-engineering for a 3-value closed set. |
| `GroupPermission` as a separate entity | **No** | See §5.1 — permissions are derived from role + the 3 settings, not stored as discrete permission rows. A generic permission table is exactly the "30 configuration flags" trap the brief warns against. |

### 5.1 Dynamic settings — what's actually necessary for V1

`chat_groups.who_can_invite` already exists (`OWNER_ADMIN_ONLY | ALL_MEMBERS`). Two more ENUM columns cover the settings that matter for V1, following the exact same column-per-policy convention already established rather than inventing a generic flexible settings blob:

- `who_can_send_messages ENUM('EVERYONE','ADMINS_ONLY') DEFAULT 'EVERYONE'` — new
- `who_can_edit_group_info ENUM('OWNER_ADMIN_ONLY','ALL_MEMBERS') DEFAULT 'OWNER_ADMIN_ONLY'` — new

That's 3 total settings columns for V1. `who_can_approve_join_requests` and `join_policy` are designed conceptually (§9) but not added to the V1 schema — they only become meaningful once join requests/invite links exist (Future). This keeps the settings surface small and extensible (adding a 4th ENUM column later is cheap) without pre-building a generic key-value settings system nobody needs yet.

---

## 6. Owner / Admin / Member — permission matrix

**⚠️ REQUIRES YOUR APPROVAL.** This is a genuine design judgment call, not derived from the existing code (there's no prior art for roles anywhere in ConnectX). Recommendation below; the two rows marked (\*) are the ones most likely to be debated.

| Action | Owner | Admin | Member |
|---|---|---|---|
| Send message | Always | Always | Per `who_can_send_messages` |
| Add member (invite) | Always, any user | Always, any user | Only own connections, and only if `who_can_invite = ALL_MEMBERS` (see §7) |
| Remove member | Any member incl. admins | Members only (not owner, not other admins) | No |
| Approve join request (Future) | Yes | Yes | No |
| Invite via link (Future) | Yes | Per link-generation setting (Future) | No |
| Change group name/photo/description | Always | Per `who_can_edit_group_info` | Per `who_can_edit_group_info` |
| Change group settings (the 3 ENUMs) | **Owner only** (\*) | No | No |
| Promote member → admin | Yes | **No** (\*) | No |
| Demote admin → member | Yes | No | No |
| Transfer ownership | Yes (to a current admin or member) | No | No |
| Delete group | Yes | No | No |
| Pin message | Yes | Yes | No |
| Leave group | Yes, triggers ownership-transfer flow (§10) | Yes | Yes |

**(\*) Why owner-only for settings, and why admins can't promote other admins:** both are deliberate anti-privilege-escalation choices — an admin who could grant other admins, or loosen `who_can_send_messages`/`who_can_invite` unilaterally, could silently dilute the owner's control of a group they didn't create. This mirrors how the codebase already treats blocking/connection-removal as strictly self-scoped (never delegable). If this is too conservative for the intended product feel (e.g., large community groups where the owner is often absent), the alternative — letting admins promote/demote and edit settings — is a one-line change to the authorization check, not a schema change. Flagging for your call rather than assuming.

---

## 7. Connection-aware invitation policy

**⚠️ REQUIRES YOUR APPROVAL.**

Worked example from the brief: A creates Group X, adds B. B and C are connected; A is not connected to C. B wants to add C.

**Recommendation:** invitation *initiation* rights are governed by role, but a **MEMBER (not owner/admin) may only invite users they are personally connected to** — owner/admin may invite anyone (subject to the invitee's own future privacy setting, §8), reflecting their elevated responsibility for the group's composition.

- `who_can_invite = OWNER_ADMIN_ONLY`: B cannot invite anyone, connected or not. C must be invited by A (or a promoted admin).
- `who_can_invite = ALL_MEMBERS`: B may invite C **because B and C are connected**. If B and C were not connected, B could not invite C even with this setting — B would need to ask an owner/admin, who can invite regardless of connection state.

This directly resolves the worked example (B can add C only via the connection) without needing a separate "group invite permission" concept — it composes `who_can_invite` (a role gate) with the existing `UserConnectionRepository` check (a relationship gate), reusing `existsByUserLowIdAndUserHighId` exactly as `MessageService`'s DIRECT block does today.

**What happens after B initiates the invite:** the invitee **always** explicitly accepts or rejects — nobody is silently added, regardless of who invited them. This matches the existing `group_invitations` PENDING→ACCEPTED/REJECTED/CANCELLED shape and the UX precedent already set by `connection_requests`. No additional admin-approval-of-the-invite-itself in V1 — that would double-gate the same action `who_can_invite` already gates.

---

## 8. User-level group privacy

**⚠️ REQUIRES YOUR APPROVAL** (design only — not enforced in V1, see §31).

Proposed setting, slotting into `SettingsModal`'s existing "Privacy" section next to `profilePhotoVisibility` (same UI pattern, same `userApi.updateProfile`-style persistence): **"Who can add me to groups"** — `ANYONE | MY_CONNECTIONS | NOBODY` (require a join request instead, once that exists).

**Interaction with invite links (Future) — worked example from the brief:** admin A (not connected to B) sends an invite *link*; B has "only connections can add me."

**Recommendation:** a privacy setting governing *targeted* invites should **not** block *voluntary* link use — clicking a link is the invitee's own affirmative choice, not someone unilaterally adding them. This is also the behavior every mainstream group-chat product uses (WhatsApp/Telegram/Discord all let links bypass contact-based add restrictions), so it won't surprise users. Concretely, for link-joins only: `ANYONE`/`MY_CONNECTIONS` → instant join via link (both already imply "I'm open to being reached"); `NOBODY` → link-join routes into a join-request/admin-approval step instead of instant membership, respecting the "always require my explicit approval" intent without literally blocking a link the user chose to click. Flagging because "NOBODY should mean nobody, full stop, even via link" is a defensible alternative reading — your call.

---

## 9. Invitation lifecycle (V1 — via `group_invitations`, already schema-ready)

States: `PENDING → ACCEPTED | REJECTED | CANCELLED`. **No `EXPIRED` state** — recommend not adding one for V1 (mirrors `connection_requests`, which also has no EXPIRED; stale PENDING rows are harmless and can be cancelled by the inviter or simply ignored, avoiding a background expiry job for V1).

| Scenario | Resolution |
|---|---|
| B already a member | Reject invite creation with `409 ALREADY_MEMBER` (mirrors `ALREADY_CONNECTED`) |
| B already has a PENDING invite to this group | Reject with `409 INVITATION_ALREADY_PENDING` (enforced by the existing `uk_groupinv_pending_pair` generated-column unique index — same partial-unique-on-PENDING pattern as `connection_requests`) |
| B was previously removed | Allowed — a fresh invite creates a new PENDING row; past removal doesn't block re-invitation (no "banned" concept in V1, see §10) |
| A blocks B, or B blocks A | Reject invite creation with `403 BLOCKED` (reuses `existsEitherDirection`, same precedence as connection requests — checked before membership/duplicate checks) |
| Invite cancelled by inviter | `CANCELLED`, terminal |
| Invite accepted | `ACCEPTED`, terminal; creates the `ConversationMember` row (role `MEMBER`) and triggers group-key distribution (§15) |
| Invite rejected | `REJECTED`, terminal |

## 9.1 Join request lifecycle (designed now, **built Future**, not V1)

Only relevant once a self-service join path exists (invite links, or a "connections can join automatically" group policy). Shape, for completeness:

New table `group_join_requests`: `id, group_id, requester_user_id, status ENUM(PENDING,APPROVED,REJECTED,CANCELLED), requested_at, responded_at, responded_by_user_id`. Same partial-unique-on-PENDING pattern as `group_invitations` (one PENDING request per (group, requester) pair). Owner and admins can both approve/reject (no owner-only restriction here, unlike settings changes — approving a join request is lower-stakes and higher-volume than changing group policy). Duplicate requests while one is already PENDING are rejected the same way duplicate connection requests are. After rejection, the requester may request again (no permanent ban without an explicit "removed/banned" concept, which V1 doesn't have — see §10).

---

## 10. Member removal and ownership

| Scenario | Resolution |
|---|---|
| Owner removes a member (incl. admin) | Allowed (§6 matrix) |
| Admin removes a member | Allowed, **except** another admin or the owner — admins cannot remove admins or the owner |
| Member leaves voluntarily | Always allowed for members/admins |
| Owner leaves | **Must transfer ownership first** — the leave action for an owner is blocked (`409 OWNER_MUST_TRANSFER_FIRST`) unless they explicitly transfer to another admin/member in the same flow, or the group is being deleted instead. This avoids an ownerless group, which would otherwise require ad-hoc "who's in charge now" logic. |
| Admin leaves | Allowed; role simply vacates, no cascading effect on other admins/owner |
| Last member leaves | Group (and its `Conversation`/`chat_groups` row) is soft/hard-deleted the same way `ConversationService.deleteConversationForUser`'s existing "GC when last active member leaves" logic already works for DIRECT (§3) — reused, not reinvented |
| Owner account becomes unavailable (deleted/deactivated) | Out of scope for V1 — ConnectX has no account-deletion flow today for any user, so this has no existing precedent to extend. Flag as a Future concern once account deletion exists at all. |

**Ownership transfer rules:** owner-initiated only, target must be a current member (any role — transferring to a plain member is allowed, it promotes them to owner atomically), old owner becomes `ADMIN` (not demoted to plain member — they were trusted enough to found the group). Race-safety: same pessimistic-lock pattern as `createOrGetDirectConversation`'s pair-lock (§24), locking the `chat_groups` row for the duration of the transfer to prevent two concurrent transfers from both succeeding.

---

## 11. Blocking + Groups

**⚠️ REQUIRES YOUR APPROVAL.** This is explicitly flagged by the brief as needing careful, non-DIRECT-copy-paste design.

**Recommendation: a 1:1 block has no retroactive effect on existing shared group membership.** A and B both already in Group X; A blocks B:

- **Both remain members.** Forcibly removing someone from a shared group as a side effect of an unrelated 1:1 block would surprise every other member and override the group admins' authority over membership — a block is a private, unilateral, user-scoped action; group membership is a shared, admin-governed one. They shouldn't cascade into each other.
- **Messages still deliver and render normally in the group**, for both directions. A shared thread with silently-hidden messages from one specific viewer would make the conversation inconsistent (reactions/replies referencing a message A can't see, read receipts that don't line up) and is a materially bigger UX/consistency problem than in DIRECT (where blocking simply ends the 1:1 relationship entirely).
- **Profile photo visibility is unaffected by this decision — it already works correctly for free.** `ProfileVisibilityService.isProfilePhotoVisible` already checks `existsEitherDirection` first and returns `false` before checking anything else (`user/service/ProfileVisibilityService.java`, confirmed in the pre-Groups audit). Once group member avatars reuse the same `PublicUserDto`/`resolvePhotoVisibility` machinery (§18), a blocked pair's photos stay hidden from each other inside a group automatically, with zero group-specific code.
- **What blocking *does* change going forward:** neither can invite the other to a **new** group (reuses the exact `existsEitherDirection` check `MessageService`/`ConnectionService` already apply, just added to group-invite creation), and neither can newly connect/message each other outside the group, exactly as today.
- **Mentions** (Future feature, not V1): no special restriction recommended — the pair are still legitimate co-members; if this feels wrong once mentions ship, revisit then rather than pre-deciding for a feature that doesn't exist yet.

**Rejected alternative** (noted for your visibility): auto-hide a blocked member's messages or force-remove them from shared groups. Rejected because it's inconsistent with "blocking is a private, personal filter, not a group-governance action," and because groups already have an explicit mechanism for removing someone — an admin who agrees the person shouldn't be there can use it.

---

## 12. Group message authorization (backend, authoritative)

Extends `MessageService.sendMessage`'s existing, already-anticipated branch point (§4) — the DIRECT branch stays untouched; a new sibling branch is added:

```
if (conversation.getType() == DIRECT) {
    // existing: block-check, connection-check (unchanged)
} else if (conversation.getType() == GROUP) {
    // membership already checked above, unconditionally, for every type
    if (chatGroup.getWhoCanSendMessages() == ADMINS_ONLY) {
        require member.getRole() in {OWNER, ADMIN}, else 403 SEND_NOT_PERMITTED
    }
    // no block/connection check between arbitrary member pairs -- membership
    // itself (via an accepted invitation) is the authorization, exactly as
    // the pre-existing code comment already anticipated
}
```

Full chain per message, backend-authoritative, frontend never trusted (per the brief's explicit requirement): (1) authenticated user resolved from JWT — existing; (2) conversation exists — existing; (3) `conversation.getType() == GROUP` — existing field, new branch; (4) `existsByConversationIdAndUserIdAndDeletedAtIsNull` — existing query, already unconditional; (5) `who_can_send_messages` role check — new; (6) no separate "suspended" check needed in V1 — there's no suspension concept, only membership presence/absence (removed = no member row = fails check 4); (7) group settings permit messaging — same as (5).

---

## 13. Real-time WebSocket authorization

**This closes a real, currently-live gap — not a hypothetical.** Confirmed by direct inspection of `WebSocketAuthChannelInterceptor.preSend`: its entire body is gated behind `if (StompCommand.CONNECT.equals(accessor.getCommand()))`. For `SUBSCRIBE` (or any other command), the method falls through to `return message;` unmodified — **zero membership check exists today for `/topic/conversation/{id}` SUBSCRIBE, for DIRECT or GROUP.** Any authenticated client can subscribe to any conversation's topic by id and receive its live message/reaction/pin/edit broadcasts, regardless of membership.

**This must be fixed before group topics exist, and arguably should ship as an independent fix regardless of Groups timing** (it's a real DIRECT-conversation privacy gap today, not something Groups introduces).

**Design:**

```
CONNECT
→ authenticate JWT (existing, unchanged)

SUBSCRIBE /topic/conversation/{id}
→ NEW: extract {id} from the destination header
→ NEW: resolve the authenticated principal set at CONNECT (accessor.getUser())
→ NEW: existsByConversationIdAndUserIdAndDeletedAtIsNull(id, principal.getId())
→ if false: suppress the frame (return null from preSend) -- no ERROR frame,
  to avoid confirming to a non-member whether conversation {id} even exists
→ if true: pass through unmodified (existing behavior)

SEND /app/message.send
→ unchanged -- MessageService.sendMessage already re-checks membership on
  every send (§12), independent of whatever the client subscribed to
```

**Explicitly protects against** (per the brief's checklist): subscribing to another group's topic (blocked by the new SUBSCRIBE check); sending to another group (already blocked today by `MessageService`'s existing membership check, confirmed, no change needed); a removed member continuing to receive messages (removal deletes/soft-deletes their `ConversationMember` row — their next SUBSCRIBE attempt, e.g. after a reconnect, fails the new check; their *existing* live subscription from before removal is not automatically torn down mid-session in V1 — see below); stale subscriptions; forged conversation IDs (the check is server-side, ID is just an opaque lookup key, forging it only ever produces "not found" or "not a member," never a bypass).

**Known V1 limitation, explicitly flagged rather than silently accepted:** removing a member does not proactively force-unsubscribe their *already-connected* WebSocket session mid-flight — the SUBSCRIBE check only re-runs on the next SUBSCRIBE attempt (e.g. reconnect, or switching away and back to that chat). A just-removed member with an open tab could keep receiving live broadcasts on `/topic/conversation/{id}` until their session naturally reconnects or they close/reopen the conversation. Closing this fully would require the backend to proactively track and terminate a specific STOMP session's subscription on removal — a larger addition (session registry keyed by conversation id) not justified for V1 given the removed member already loses `POST`-path access (can't send, can't fetch new pages via REST) and the exposure window is small and self-limiting. Recommend accepting this gap for V1 and revisiting only if it proves to matter in practice.

---

## 14. Group E2EE — approach comparison

**THIS IS THE MOST CONSEQUENTIAL DECISION IN THIS DOCUMENT. ⚠️ REQUIRES YOUR APPROVAL.**

**Grounding fact that changes the calculus, confirmed by direct inspection of both backend and frontend code (not assumed):** ConnectX's *existing* DIRECT E2EE already stores the user's master **private** key server-side. `User.masterPrivateKey` is a plain `@Lob TEXT` column (`user/entity/User.java`), returned in plaintext JSON by `GET /users/me/identity-key`, and the frontend's `deviceSession.ts` explicitly documents why: *"All devices/browsers belonging to the user share the exact same E2EE identity key, allowing 100% of messages to be decrypted seamlessly on any device."* On every login, if the server already has a `masterPrivateKey`, the client **imports the server-supplied private key** into its local vault. This is a deliberate design choice for multi-device convenience, not a bug — but it means the server, as currently architected, already has the technical capability to decrypt any DIRECT message if it chose to misuse that key. (Per-*device* keys, by contrast, genuinely are public-only server-side — `Device.publicKey`, no private column — but the app doesn't actually use per-device key diversity for encryption; it uses the shared master key.)

This matters directly for group crypto: pursuing MLS-grade forward secrecy and post-compromise security for **groups** while DIRECT messaging already trusts the server with full decrypt capability would be inconsistent — a large complexity investment that doesn't change the actual trust boundary of the application, since the same server already has (or could have) plaintext access via the escrowed master key regardless of how sophisticated the group scheme is. The right target for V1 is a scheme that is *at least as good as* the existing DIRECT model, reuses its exact primitives, and is honest about not exceeding it — not a scheme that pretends groups need stronger properties than 1:1 chats already have today.

| | A. Pairwise per member | B. Shared symmetric key | C. Sender Keys | D. Key wrapped per member | E. MLS |
|---|---|---|---|---|---|
| **Mechanism** | Encrypt the message N times, once per recipient's pairwise channel | One AES key per group, message encrypted once | One symmetric key per (group, sender), rotated per epoch | One AES group key, but only the *key itself* is encrypted N times (once per member, via existing pairwise channels) — the message body is encrypted once | Tree-based group key agreement (RFC 9420) |
| **Security** | Equal to DIRECT | Equal to DIRECT (same key custody model as A/D once distributed) | Better: per-sender authentication, no shared secret all members hold | Equal to DIRECT | Strong forward secrecy + post-compromise security |
| **Performance / send** | O(N) crypto ops per message — doesn't scale | O(1) — one AES-GCM op per message | O(1) per message, O(N) on sender-key (re)distribution | **O(1) per message** — one AES-GCM op, same cost as a DIRECT message today | O(1) per message but heavy per-epoch tree math |
| **Member add** | Trivial (no shared state) | Re-share current key with new member only | New member gets senders' current keys | Wrap current group key for new member only (1 pairwise-encrypt op) | Tree update, O(log N) |
| **Member remove (forward secrecy)** | N/A (no shared key to leak) | Must rotate + redistribute to all remaining members, or removed member can decrypt future messages | Removed member's future messages unreadable once their key epoch is retired; others unaffected | Must rotate (generate new AES key) + re-wrap for all remaining members | Native, efficient tree-based rotation |
| **Forward secrecy** | None (matches DIRECT today) | None without rotation-on-every-message (impractical) | Partial (per-sender) | None between rotations — **acceptable only if rotated on every removal** | Strong |
| **Post-compromise security** | None | None | Partial | None | Strong |
| **Offline users** | Fine — no shared state to miss | Fine — key delivered whenever they next connect | Fine | Fine | Requires careful out-of-order-epoch handling |
| **Device changes** | Re-derive per pairwise channel | Re-wrap key for the (shared-identity) user again | Re-share sender keys | Re-wrap key again — same op as "add member" | Complex device/client tree membership |
| **Multi-device** | Works, since ConnectX already shares one identity key across devices | Same | Same | Same | Needs explicit multi-device tree support (not designed for shared-identity model) |
| **Complexity vs. existing ConnectX crypto layer** | Low, but doesn't scale | Low | Medium-high (new primitive: sender-key rotation, epoch tracking) | **Lowest — reuses `encryptMessage`/`decryptMessage` unmodified, zero new WebCrypto primitives** | Very high — effectively a new crypto library, no existing building block in this codebase |
| **Fits existing `messages` table (1 ciphertext/row)?** | Yes, but N rows or N ciphertext fields needed — breaks the schema | Yes, unmodified | Yes, unmodified | **Yes, unmodified** | Yes, unmodified |

### Recommendation: **D — shared AES-256-GCM group key, wrapped per member using the existing pairwise ECDH primitives**

- **Message body:** generate one AES-256-GCM key per group at creation time (client-side, by the creator — never transmitted in plaintext). Every group message is encrypted with this key exactly once, using the **exact same WebCrypto AES-GCM call the app already makes for DIRECT messages** — `messages.ciphertext`/`.nonce`/`.encryptionAlgorithm` need zero schema change (§16).
- **Key distribution:** the group key itself is encrypted once per member using `encryptMessage(creatorPrivateKey, memberPublicKeyBase64, groupKeyBase64)` — literally the existing pairwise primitive, called N times at creation/membership-change time instead of once per DIRECT message. Stored in a new small table (§21), one wrapped copy per member.
- **Rotation policy (the one property that actually matters): rotate the group key when a member is *removed*** (generate a new key, re-wrap for all *remaining* members) so a removed member cannot decrypt anything sent after their removal. **Do not rotate on every *add*** — a new member joining doesn't need forward secrecy for messages sent before they existed (they simply don't have the old key and can't decrypt history anyway, which is the correct behavior — new members shouldn't see pre-join history in most group chat products). This asymmetry (rotate-on-remove, not rotate-on-add) is the one piece of this recommendation most worth double-checking against your product intent.
- **Why not C (Sender Keys)** for V1: better properties, but introduces a genuinely new crypto concept (per-sender epochs, rotation bookkeeping) this codebase's crypto layer has never needed before, for a benefit (per-sender authentication, partial forward secrecy) that's inconsistent with the fact that the server already escrows the master private key anyway. Worth revisiting as a V2 crypto upgrade once/if the underlying key-escrow model is also revisited.
- **Why not E (MLS):** would be the "textbook correct" choice in a vacuum, but is a multi-month undertaking requiring a new crypto library this codebase has no foundation for, and its strongest properties (forward secrecy, post-compromise security) are moot while the server holds the master private key. Recommending against it for V1 as disproportionate to the actual current trust model.

---

## 15. Group key lifecycle

| Event | Key handling |
|---|---|
| Group created | Creator generates a fresh AES-256-GCM key client-side, wraps it for themselves + every initial member (pending their invite acceptance — see below), `chat_groups.key_version = 1` |
| Member invited (PENDING) | No key access yet — an invitee cannot decrypt anything until they accept |
| Invite accepted | Inviter (or any current member who already holds the current key) wraps the **current** group key for the new member; new member's wrapped-key row inserted; `key_version` unchanged (no rotation on add, §14) |
| Member removed | Any remaining owner/admin (whoever performs the removal) generates a **new** group key, wraps it for every remaining member, `key_version` increments; old wrapped-key rows for the removed member are deleted; the removed member's client retains old messages (already-decrypted, cached locally, per existing `decrypted_messages` IndexedDB store) but cannot decrypt anything sent after rotation |
| Member leaves voluntarily | Same as removal — rotate, since the security property ("can't decrypt future messages") should hold regardless of who initiated the departure |
| Admin promoted/demoted | **No key rotation** — role changes don't change key custody; an admin has the same group key a member had |
| Ownership transferred | No key rotation — ownership is an authorization concept, not a key-custody boundary in this design |
| New device joins same account | Reuses the existing shared-master-identity mechanism (§14 grounding fact) — since all of a user's devices already share one identity key, a new device automatically gets access to every group key the account already has, the same way it already gets DIRECT message history today. No group-specific work needed. |
| User loses a device | No effect on the group key (the shared-identity model means losing one device doesn't lose the key — it's recoverable via `GET /users/me/identity-key` on a new device, exactly as today). If the *account itself* is compromised, that's equivalent to the DIRECT compromise scenario already inherent to the master-key-escrow model — out of scope for Groups to solve. |

---

## 16. Group message storage

**Reuse `messages` unmodified. Do not create a `group_messages` table.**

```
messages
├── conversation_id   → for DIRECT: exactly 2 members. For GROUP: N members.
├── sender_id
├── ciphertext         → one AES-256-GCM ciphertext, encrypted with the group's
│                         current key (recommendation D, §14) -- structurally
│                         identical to a DIRECT ciphertext row
├── nonce
├── encryption_algorithm
└── timestamps
```

This works precisely *because* recommendation D encrypts the message body once regardless of member count — the per-recipient fan-out happens only at key-wrapping time (a small, separate table, §21), never at message-send time. If a different E2EE approach (A: pairwise-per-member) had been chosen, this section's answer would be "no, `messages` cannot be reused as-is" — worth restating since it's the reason D is structurally, not just cryptographically, the better fit.

---

## 17. Group notifications

| Event | Push? | Design |
|---|---|---|
| New message | **Yes** | Reuses the existing per-member `sendPushToUserAsync` loop (§3) unmodified; only the title/body formatting changes (`"GroupName"` / `"SenderName: message"` instead of `"SenderName"` / `"message"`) |
| Mention (Future) | Future | Requires message-text parsing for `@username`, not in V1 |
| Invitation received | **Yes** | New — single-recipient push to the invitee, reuses the existing single-recipient push call exactly as-is |
| Join request received (Future) | Future | Paired with the join-request feature |
| Member added/removed/role changed/settings changed | **No push** — recommend an in-thread system message instead (e.g. *"Alice added Bob to the group"*), rendered client-side like a normal message but without ciphertext, mirroring the well-established pattern most group chat products use for these events. Avoids notification fatigue for low-signal events; still visible to anyone with the chat open, and appears in scrollback/history for anyone who reopens it. | If currently viewing the group, this can also drive a soft local toast — but never a push. |

---

## 18. Group Contact Info UI

**Do not reuse `ContactInfoDrawer` — confirmed by inspection it's built entirely around a single `recipient: User`** (key fingerprint from `publicKeys[0]`, device list for that one user, block/unblock/remove-connection all singular-target). A new `GroupContactInfoDrawer` component is warranted, not a retrofit.

Structure (per the brief):

- **Group header** — group photo (reuses the existing `ProfileImageStorage`/upload pattern, just keyed by conversation id instead of user id), group name, member count, description.
- **Actions** — Search (in-chat message search, if it exists for DIRECT — reuse), Mute, Notifications toggle, **Export Chat is explicitly NOT included** (DIRECT-only per §4/§32), Leave Group.
- **Members list** — grouped/sorted Owner → Admins → Members, each row reusing `UserAvatar`/`PublicUserDto` (so block-aware photo visibility, §11, applies automatically), with a member-search input for large groups.
- **Admin controls** — promote/demote/remove buttons rendered *only* when the viewer's own role authorizes them (§6 matrix) — never rendered-but-disabled; absent entirely for members, since a visible-but-disabled admin control is itself a minor information leak about the UI's capability surface and adds confusing chrome for someone who will never have permission.
- **Settings section** — the 3 dynamic settings (§5.1), rendered only for Owner (per §6's "settings = owner only" recommendation); read-only display of current settings for everyone else, so members can at least see the group's policies without being able to change them.

The UI should be driven by a single `myRole: 'OWNER' | 'ADMIN' | 'MEMBER'` + `groupSettings` payload from the group-details endpoint (§22), computed server-side — never inferred client-side from a stale member list, consistent with "frontend is never the final authority" (§12).

---

## 19. Group member limit

**⚠️ REQUIRES YOUR APPROVAL.** Recommendation: **100**.

- **25** — too restrictive for a general-purpose group chat (family + extended family, a small team, a friend group easily exceeds this); would feel artificially limiting.
- **50** — workable, but doesn't provide much headroom over 25 for the added complexity of getting groups right.
- **100 (recommended)** — comfortably covers realistic V1 use cases (teams, friend groups, communities) while keeping every O(N) operation cheap: message-send fan-out (already a plain per-member loop, confirmed, §3), push-notification fan-out (same loop, one HTTP POST per subscription), and group-key wrapping at creation/rotation time (100 pairwise-encrypt operations, sub-second on any modern device/server). None of these are optimized/batched today, and 100 is small enough that they don't need to be for V1.
- **250** — starts to strain the *unbatched* key-wrapping step specifically (250 sequential ECDH+AES-GCM wraps client-side on every removal-triggered rotation is a noticeably longer client-side pause) and meaningfully increases push-notification volume per message. Would want batching/async work before going this high.

Recommend storing the limit as an application constant (not a per-group DB column) for V1 — simplest, and matches "avoid 30 configuration flags." Trivial to make configurable later if needed.

---

## 20. Future WebRTC calls — compatibility

**Not designed or implemented here — compatibility assessment only, as instructed.**

**Reusable directly:**
- JWT authentication (unchanged — call signaling rides the same authenticated WebSocket session).
- Group/conversation membership as the participant-authorization source of truth — "who may join this call" is just "who is currently an active member of this conversation," the same check §12/§13 already establish.
- The WebSocket channel itself for **signaling** (SDP offer/answer, ICE candidates) — a new `/app/call.signal` STOMP handler and a per-call topic (e.g. `/topic/call/{callId}`) fit the exact SUBSCRIBE-authorization model being built in §13 for message topics. **The §13 fix is a direct prerequisite for call signaling too**, not just group messaging — worth noting as an argument for prioritizing §13 independent of Groups' own timeline.
- Participant authorization logic (who can join, who can be removed from a call) — same role/membership model as §6.

**Must differ:**
- **DIRECT calls (2 participants)** can use simple mesh/P2P WebRTC — one peer connection, no new infrastructure.
- **GROUP calls (3+ participants)** cannot scale via mesh (O(N²) peer connections and each participant's uplink bandwidth) past roughly 4-6 people. This requires an **SFU (Selective Forwarding Unit)** — a media-relay server component ConnectX does not have today in any form (WebSocket signaling is not a media relay; no media server exists in this codebase). This is a **separate, major infrastructure project** (self-hosted SFU like mediasoup/LiveKit, or a managed service), not "the same WebRTC code with more participants."
- Call state (ringing/active/ended/participant list/mute state) needs new entities and WS message types — natural extensions of the existing conversation/membership model, but not yet designed.
- Scaling/participant-management concerns (who's speaking, active-speaker detection, bandwidth adaptation) are entirely SFU-side concerns with no analog in the current codebase.

**Recommendation:** design DIRECT calls and GROUP calls as explicitly separate initiatives when the time comes — DIRECT-call mesh WebRTC is a comparatively small addition once §13's signaling-topic authorization exists; GROUP calls should be scoped and evaluated (including an SFU build-vs-buy decision) as its own project, not bundled into Groups V1 or even Groups V2.

---

## 21. Database design (conceptual, not yet applied)

Building on Stage 0B's existing schema (`chat_groups`, `group_invitations`, `conversation_members.role`/`.invited_by_user_id` — all already present, per §3).

**New JPA entity mappings (no DDL change):**
- `ChatGroup` entity → `chat_groups` (existing columns) + 2 new columns:
  - `who_can_send_messages ENUM('EVERYONE','ADMINS_ONLY') NOT NULL DEFAULT 'EVERYONE'`
  - `who_can_edit_group_info ENUM('OWNER_ADMIN_ONLY','ALL_MEMBERS') NOT NULL DEFAULT 'OWNER_ADMIN_ONLY'`
  - `key_version INT NOT NULL DEFAULT 1` — new; cheap client-side cache-invalidation signal for the group key (§15) — a member's cached key is stale if `key_version` on the group doesn't match what they last fetched.
- `ConversationMember.role` (`GroupRole` enum reusing the existing native `ENUM('OWNER','ADMIN','MEMBER')`) and `.invitedByUserId` — map onto the existing unmapped columns, no schema change.
- `GroupInvitation` entity → `group_invitations` (existing columns, no change).

**New table (V1):**
```sql
CREATE TABLE group_member_keys (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  member_user_id BIGINT NOT NULL,
  wrapped_key TEXT NOT NULL,        -- the group's AES key, ECDH-wrapped for this member
  key_version INT NOT NULL,         -- must match chat_groups.key_version for the key to be current
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_groupkey_member (conversation_id, member_user_id),  -- one row per member, overwritten on rotation
  CONSTRAINT fk_groupkey_conversation FOREIGN KEY (conversation_id) REFERENCES chat_groups (conversation_id) ON DELETE CASCADE,
  CONSTRAINT fk_groupkey_member FOREIGN KEY (member_user_id) REFERENCES users (id) ON DELETE CASCADE
);
```
One row per (group, member) — overwritten in place on rotation (§15), not versioned history, since only the *current* key ever needs to be fetched (old messages stay decrypted client-side already, per the existing `decrypted_messages` IndexedDB cache — the server never needs to hand out a historical key).

**Future table (designed, not built, §9.1):**
```sql
CREATE TABLE group_join_requests (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_id BIGINT NOT NULL,
  requester_user_id BIGINT NOT NULL,
  status ENUM('PENDING','APPROVED','REJECTED','CANCELLED') NOT NULL,
  requested_at DATETIME(6) NOT NULL,
  responded_at DATETIME(6) DEFAULT NULL,
  responded_by_user_id BIGINT DEFAULT NULL,
  pending_request_key VARCHAR(41) GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING' THEN CONCAT(group_id, '_', requester_user_id) ELSE NULL END
  ) VIRTUAL,
  UNIQUE KEY uk_groupjoin_pending (pending_request_key),
  CONSTRAINT fk_groupjoin_group FOREIGN KEY (group_id) REFERENCES conversations (id) ON DELETE CASCADE,
  CONSTRAINT fk_groupjoin_requester FOREIGN KEY (requester_user_id) REFERENCES users (id) ON DELETE CASCADE
);
```
Same partial-unique-on-PENDING pattern as `group_invitations`/`connection_requests`.

**Worth adding regardless of Groups (found during this review, not a new requirement):** `conversation_members` has no unique constraint on `(conversation_id, user_id)` — soft-delete (`deleted_at`) allows legitimate re-adds, so a plain unique constraint would be wrong, but a **partial unique index on active rows only** (same VIRTUAL-generated-column trick as above, `active_pair_key = CASE WHEN deleted_at IS NULL THEN CONCAT(conversation_id,'_',user_id) ELSE NULL END`) would close a real race window: two concurrent "add member" calls for the same (group, user) pair today rely entirely on an app-level `existsByConversationIdAndUserIdAndDeletedAtIsNull` check with no DB backstop, unlike every other insert-uniqueness case in this codebase (§24). Recommend adding this as part of the Groups migration even though it also benefits DIRECT.

---

## 22. API design

REST for every mutating action (consistent with how connections/blocking are REST today); WebSocket only for the resulting real-time broadcast, never as the primary write path — mirrors the existing `MESSAGE_REACTION_UPDATE`-after-REST-mutation pattern.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/groups` | Create group (name, description?, initial memberIds via invitations) |
| `GET /api/v1/groups/{conversationId}` | Group details + caller's role + settings |
| `PATCH /api/v1/groups/{conversationId}` | Update name/description/avatar (per `who_can_edit_group_info`) |
| `PATCH /api/v1/groups/{conversationId}/settings` | Update the 3 ENUM settings (owner only) |
| `GET /api/v1/groups/{conversationId}/members` | Member list with roles |
| `POST /api/v1/groups/{conversationId}/invitations` | Create a `GroupInvitation` (§7's connection-aware check applies here) |
| `GET /api/v1/groups/invitations/pending` | My incoming pending group invitations |
| `GET /api/v1/groups/invitations/sent` | My outgoing pending group invitations |
| `POST /api/v1/groups/invitations/{id}/accept` | Accept — creates membership + triggers key wrap (§15) |
| `POST /api/v1/groups/invitations/{id}/reject` | Reject |
| `POST /api/v1/groups/invitations/{id}/cancel` | Inviter/admin cancels |
| `DELETE /api/v1/groups/{conversationId}/members/{userId}` | Remove member (or self = leave) — triggers key rotation (§15) |
| `POST /api/v1/groups/{conversationId}/members/{userId}/promote` | Owner only |
| `POST /api/v1/groups/{conversationId}/members/{userId}/demote` | Owner only |
| `POST /api/v1/groups/{conversationId}/transfer-ownership` | `{newOwnerUserId}`, owner only |
| `DELETE /api/v1/groups/{conversationId}` | Delete group, owner only |
| `GET /api/v1/groups/{conversationId}/keys/mine` | Fetch my current wrapped group key (compares `key_version`) |

**WebSocket, not REST:** message send/delivery/read-receipts/typing — unchanged, existing STOMP handlers extended per §12. **New WS broadcast event types** (server-initiated, after a REST mutation commits, to `/topic/conversation/{id}`): `GROUP_MEMBER_ADDED`, `GROUP_MEMBER_REMOVED`, `GROUP_ROLE_CHANGED`, `GROUP_SETTINGS_CHANGED`, `GROUP_INFO_UPDATED` — so an already-open group chat updates live without a poll, exactly mirroring how reactions/pins already broadcast today.

---

## 23. Authorization matrix

Backend-authoritative for every row (§12). Compressed to actor × action; group state/setting columns fold into the "condition" column rather than a full N-dimensional table, since most actions have identical logic across settings (the setting *is* the condition).

| Actor | Send message | Add/invite member | Remove member | Edit info | Edit settings | Promote/demote | Transfer/delete |
|---|---|---|---|---|---|---|---|
| Owner | Allow | Allow, any user | Allow, anyone | Allow | Allow | Allow | Allow |
| Admin | Allow | Allow if connected-or-any-user (owner/admin = any user, §7) | Allow, except owner/other admins | Allow if `who_can_edit_group_info` permits | Deny | Deny | Deny |
| Member | Allow if `who_can_send_messages = EVERYONE` | Allow only own connections, if `who_can_invite = ALL_MEMBERS` | Deny | Allow if `who_can_edit_group_info = ALL_MEMBERS` | Deny | Deny | Deny |
| Non-member | Deny (403 `NOT_CONVERSATION_MEMBER`) | N/A (can't invite into a group you're not in) | Deny | Deny | Deny | Deny | Deny |
| Invited user (PENDING) | Deny — not yet a member | N/A | N/A | N/A | N/A | N/A | N/A |
| Blocked (either direction, no shared group yet) | N/A | Deny (403 `BLOCKED`, new-invite creation only, §11) | N/A | N/A | N/A | N/A | N/A |
| Blocked (already co-members) | Allow — unaffected, §11 | Deny for *new* group invites between the pair only | Unaffected by the block itself | Unaffected | Unaffected | Unaffected | Unaffected |
| Removed former member | Deny (no member row) | N/A | N/A | N/A | N/A | N/A | N/A |

("Banned/suspended" is not a V1 concept — omitted from the matrix; removal is the only exclusion mechanism, and a removed user can always be re-invited per §9's table.)

---

## 24. Race conditions and failure handling

All reuse the codebase's two already-proven patterns (§3): `REQUIRES_NEW` self-proxy + `DataIntegrityViolationException`-catch for insert races, `Isolation.READ_COMMITTED` for cross-transaction visibility gaps after a `REQUIRES_NEW` commit.

Reference pattern in full (`MessageService.starMessage`/`insertStarInNewTransaction`, reused verbatim in concept):
```java
@Transactional
public void mutatingAction(...) {
    // ...checks...
    try {
        self.insertInNewTransaction(...);   // must go through the injected `self` proxy
    } catch (DataIntegrityViolationException e) {
        // the constraint that caught the race IS the desired end state -- log and return
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void insertInNewTransaction(...) {
    // must NOT catch its own exception -- let it propagate so Spring rolls back
    // this transaction cleanly without poisoning the outer one
}
```

| Race | Handling |
|---|---|
| Two admins add the same user simultaneously | Backed by the new partial-unique index on active `(conversation_id, user_id)` (§21) — second insert hits `DataIntegrityViolationException`, caught, treated as success (member already added, same end state) |
| Two admins remove the same user simultaneously | Plain find-then-delete, same as `ConnectionService.removeConnection` today — second caller's find comes up empty, lands on the same "not found, already removed" path, no special handling needed |
| Member removed while sending a message | `MessageService.sendMessage`'s membership check re-runs on every send (§12) — a message in flight when removal commits either lands just before (succeeds) or just after (403s), no partial/inconsistent state possible since it's a single-transaction check-then-persist |
| Member removed while subscribed | Covered by §13's known V1 limitation — not fully closed, explicitly documented there |
| Invitation accepted twice (double-click / two tabs) | Same partial-unique-on-PENDING pattern already protects `group_invitations` — second accept attempt's status-transition check (`PENDING` required) fails cleanly with `409 INVITATION_NOT_PENDING`, mirroring `REQUEST_NOT_PENDING` in `ConnectionService` |
| Invitation accepted after cancellation | Same — status check fails, `409 INVITATION_NOT_PENDING` |
| Join request approved twice (Future) | Same pattern as invitation-accepted-twice |
| Owner transfers ownership concurrently (two transfer calls) | Pessimistic lock on the `chat_groups` row for the transfer's duration (§10), mirroring `createOrGetDirectConversation`'s pair-lock — second transfer blocks until the first commits, then re-reads the (now-changed) owner and can be rejected/re-evaluated cleanly under `READ_COMMITTED` |
| Admin role changes concurrently | Simple field update, no uniqueness constraint involved — last-write-wins is acceptable (role is idempotent-to-set, unlike an insert) |
| Group deleted while a message is being sent | `chat_groups`/`conversations` FK is `ON DELETE CASCADE` — a concurrent delete either commits before the send (send then 404s on conversation lookup, existing behavior) or after (send completes, then cascade removes it) — no new handling needed, same class of "ordinary not-found race" already accepted elsewhere in this codebase |
| Member leaves while another admin removes them | Same as "remove while removing" above — idempotent, second caller sees "already not a member" |
| Stale frontend membership state | Never trusted — every mutating action re-checks server-side per §12/§23, consistent with the codebase's existing philosophy throughout (frontend relationship/connection state is explicitly documented elsewhere as "UX only, backend independently re-enforces every transition") |

---

## 25. End-to-end flow: create group

Trust boundaries marked **[TB]**.

```
1. User (owner) submits: name, description?, initial member connection ids
   [TB: client -- nothing trusted yet, just a request]

2. POST /api/v1/groups
   [TB: JWT authenticated, per-request -- existing JwtAuthenticationFilter]

3. GroupService.createGroup:
   - INSERT conversations (type=GROUP)
   - INSERT chat_groups (conversation_id, name, ..., who_can_invite=default, ...)
   - INSERT conversation_members (owner, role=OWNER)
   [TB: DB transaction boundary -- all-or-nothing]

4. For each initial member id (subject to §7's connection-aware check,
   since even the owner's "initial members" go through the same invite+accept
   flow -- no bulk-add-without-consent path exists, consistent with §7):
   - INSERT group_invitations (PENDING)
   [TB: connection/block check per invitee, same as any later invite]

5. Owner's client generates the group AES-256-GCM key locally
   [TB: key never leaves the device unencrypted]

6. Owner's client wraps the key for itself:
   encryptMessage(ownPrivateKey, ownPublicKey, groupKey) -> stored via
   POST .../keys (self-entry, so the owner can decrypt on other devices too,
   consistent with the shared-master-identity model, §14)
   [TB: wrapping happens client-side; server only ever stores ciphertext]

7. Invitation pushed to each invitee (single-recipient push, reused as-is)
   [TB: push payload contains no plaintext group key -- just "you have an invite"]

8. Invitee accepts: POST .../invitations/{id}/accept
   [TB: server verifies PENDING status + not-blocked, same checks as connection accept]

9. GroupService.acceptInvitation:
   - INSERT conversation_members (invitee, role=MEMBER)
   - UPDATE group_invitations SET status=ACCEPTED
   [TB: single transaction]

10. Invitee's client fetches the current group key wrap:
    GET .../keys/mine -> wrapped_key
    decryptMessage(myPrivateKey, wrapperPublicKey, wrapped_key) -> groupKey
    [TB: only a member with a valid wrapped-key row can ever recover the group key]

11. Any member sends a message:
    encrypt(groupKey, plaintext) -> ciphertext, nonce  [one AES-GCM op, §14/§16]
    POST /messages or STOMP /app/message.send
    [TB: MessageService re-checks membership + who_can_send_messages, §12 --
     the frontend's belief that the user is a member is never trusted]

12. MessageService persists to `messages` (unmodified schema, §16), then:
    - broadcasts to /topic/conversation/{id} (subscribers only, per §13's new check)
    - sends individually to every member's /user/queue/messages (unconditional,
      not subscription-gated -- this is why §13's gap doesn't affect delivery,
      only live-while-subscribed features)
    [TB: broadcast fan-out is server-controlled; client never fans out]

13. Each recipient's client decrypts with its own cached groupKey
    [TB: decryption happens entirely client-side, ciphertext is all the server ever handled]

14. Push notification sent to every member except sender/muted (existing loop, §3/§17)
    [TB: push payload is metadata (sender, group name preview) not ciphertext/plaintext body,
     consistent with existing DIRECT push behavior -- not re-verified in this review,
     assumed consistent with current WebPushService behavior]
```

---

## 26. Group readiness recap (from the pre-Groups checkpoint, still accurate)

Re-confirming rather than re-deriving — the pre-Groups checkpoint (`docs/CONNECTX_GROUP_IMPLEMENTATION_STATE.md`, 2026-08-18) already established these; this document is the detailed design the checkpoint said was needed next.

---

## 27. V1 scope

- Group creation (owner + invitation-based initial members)
- Invitation lifecycle: PENDING/ACCEPTED/REJECTED/CANCELLED (§9), connection-aware per §7
- Roles: OWNER/ADMIN/MEMBER, full permission matrix (§6)
- 3 dynamic settings: `who_can_invite` (exists), `who_can_send_messages`, `who_can_edit_group_info` (§5.1)
- Member management: remove, leave, promote, demote, transfer ownership, delete group (§10)
- WebSocket SUBSCRIBE authorization fix (§13) — recommend shipping this *first*, independent of Groups' own schedule, since it's a real DIRECT gap too
- Group message send/receive over existing WS+REST paths, GROUP branch in `MessageService` (§12)
- E2EE: shared AES group key, wrapped per member via existing pairwise primitives, rotate-on-remove (§14/§15) — pending approval
- Message storage: `messages` table unmodified (§16)
- Notifications: message push (existing loop, reformatted text) + invitation push; membership/role/settings changes as in-thread system messages, not push (§17)
- Group Contact Info UI: dedicated component, role-aware rendering (§18)
- Member limit: 100 (§19) — pending approval
- Race-condition handling reusing established patterns, plus the new partial-unique index on `conversation_members` (§21/§24)
- Group avatar upload — cheap to include given `chat_groups.avatar_url` already exists and the existing `ProfileImageStorage` pattern is directly reusable

## 28. Future scope

- Join requests + admin approval flow (`group_join_requests`, §9.1/§21)
- Invite links, and their interaction with user-level group privacy (§8)
- "Connections can join automatically" join policy
- User-level "who can add me to groups" *enforcement* (setting designed in §8, not enforced in V1 since V1 has no self-join path for it to matter yet)
- Mention notifications and parsing
- Sender Keys or MLS crypto upgrade (§14) — revisit only alongside a broader identity-key-escrow rework, since the current master-key-escrow model caps the real-world benefit of a stronger group scheme
- WebRTC group calls — separate infrastructure project requiring an SFU (§20); DIRECT calls (mesh) are comparatively small and could come sooner
- Proactive mid-session WebSocket subscription teardown on removal (§13's known limitation)
- Per-group configurable member limit (currently an app constant, §19)

---

## 29. Security considerations (cross-cutting summary)

- Backend is authoritative for every authorization decision (§12, §23) — frontend state is never trusted, consistent with the codebase's existing philosophy.
- WebSocket SUBSCRIBE authorization (§13) closes a real, currently-live gap affecting DIRECT conversations today, not just future GROUP topics.
- Blocking's effect on groups (§11) is deliberately narrower than DIRECT blocking — flagged for explicit approval since it's a real behavior change from what "blocking" means elsewhere in the app.
- E2EE recommendation (§14) is explicitly *not* stronger than the existing DIRECT trust model — this is a deliberate, disclosed choice, not an oversight; flagged for approval because it's the kind of decision that shouldn't be made silently.
- The pre-existing master-private-key server escrow (confirmed again during this review) is unchanged and out of scope for Groups to fix — same position as the pre-Groups checkpoint.

## 30. Performance considerations (cross-cutting summary)

- Message/push fan-out is already O(N)-but-already-generic (§3) — no redesign, just confirmed to already scale to the recommended 100-member cap (§19).
- Group key wrapping is O(N) client-side work at creation/rotation time only, not per-message — the expensive-ish operation is bounded to membership-change events, not message throughput.
- No new polling anywhere in this design — all real-time behavior rides the existing WebSocket/push infrastructure.

## 31. Migration/rollout strategy

- Schema changes are additive only (2 new columns on `chat_groups`, 1 new table `group_member_keys`, 1 new partial-unique index on `conversation_members`) — zero impact on existing DIRECT data, consistent with every prior migration in this codebase's history.
- `ChatGroup`/`GroupInvitation` JPA entities can be added and deployed with zero behavior change until the first `POST /api/v1/groups` call exists — the tables have sat unused since Stage 0B with no ill effect, confirmed.
- Recommend shipping §13 (WebSocket SUBSCRIBE authorization) as its own preliminary change, ahead of the rest of Groups, since it's independently valuable and de-risks the rest of the rollout by not bundling a security fix with a large feature.
- No feature flag infrastructure exists in this codebase today (confirmed no precedent) — recommend gating Groups' visibility the same way prior features were rolled out (a dedicated branch, merged when ready), not introducing new flag infrastructure for this alone.

---

## 32. Unresolved decisions requiring explicit approval

1. **§14 — E2EE group encryption scheme.** Recommended: shared AES-256-GCM group key, wrapped per member via existing pairwise ECDH primitives, rotate-on-member-removal only. Explicitly not stronger than the existing DIRECT trust model (which already escrows the master private key server-side) — deliberate, not an oversight.
2. **§7/§9 — Group invitation policy.** Recommended: V1 is invite-only (no self-join), members may only invite their own connections, owner/admin may invite anyone, invitee always must explicitly accept.
3. **§8 — User-level group privacy.** Recommended: `ANYONE | MY_CONNECTIONS | NOBODY` setting, designed now, enforcement deferred to Future; link-joins (Future) bypass `ANYONE`/`MY_CONNECTIONS` but route `NOBODY` through approval rather than blocking outright.
4. **§11 — Blocking behavior inside groups.** Recommended: no retroactive removal from shared groups, messages/delivery unaffected, only *new* invitations between a blocked pair are blocked; profile photo visibility already handled for free by existing `ProfileVisibilityService`.
5. **§6 — Owner/admin permissions matrix.** Recommended matrix in full; specifically flagging that admins cannot edit group settings or promote/demote other admins (owner-only) as the two rows most likely to warrant a different call.
6. **§19 — Group member limit.** Recommended: 100.

Also worth a deliberate yes/no even though not in the brief's explicit list:
7. **§13's known limitation** — accepting that a removed member's already-open WebSocket session isn't proactively torn down mid-flight for V1 (they lose write/fetch access immediately, but may keep receiving live broadcasts on their current session until it naturally reconnects). Flagged as a real, if narrow, gap rather than silently accepted.

---

## Appendix: contradiction/consistency check performed against this document

- §4's "must remain DIRECT-only" list cross-checked against §6/§12/§16 — no group-specific recommendation anywhere in this document proposes touching `createOrGetDirectConversation`, the `LEGACY_CHAT` relationship path, or chat export. Consistent.
- §14's E2EE recommendation cross-checked against §16 (storage) and §25 (end-to-end flow) — the "one ciphertext per message regardless of member count" property is used consistently in both; no section assumes a different storage shape than §16 defines.
- §6's "admins cannot edit settings" cross-checked against §22's API design — `PATCH .../settings` is documented as owner-only, matching §6, not left ambiguous.
- §11's "blocking doesn't force removal" cross-checked against §23's authorization matrix — the matrix's "blocked, already co-members" row explicitly states messaging is unaffected, consistent with §11's prose.
- §19's 100-member recommendation cross-checked against §14/§15's key-wrapping cost and §3's confirmed-generic push-fan-out loop — no section assumes a different member count ceiling.
- No section in this document proposes modifying E2EE primitives, WebSocket transport/message semantics (only authorization, additively), Web Push transport, the service worker, or existing DIRECT/connection/blocking logic — confirmed against §2's non-goals.
