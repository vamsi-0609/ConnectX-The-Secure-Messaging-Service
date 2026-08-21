# CONNECTX — REAL-TIME / REST ARCHITECTURE DECISION AUDIT
## Comprehensive Forensic Analysis & Engineering Blueprint

> **DOCUMENT TYPE**: Architectural Audit & Design Specification  
> **AUDIT CLASSIFICATION**: READ-ONLY / NON-DESTRUCTIVE  
> **TARGET AUDIENCE**: Architecture Review (Gemini) & Phased Implementation Team (Claude)  
> **SOURCE OF TRUTH**: Current ConnectX Baseline (`connectx-backend` + `connectx-frontend`)  
> **ANALYSIS DATE**: August 2026

---

## 1. Executive Summary

### 1.1 Core Finding
ConnectX currently has a **split-brain real-time architecture**:
1. **Core Messaging & Presence** operates on a functional WebSocket (STOMP over SockJS) event pipeline with transactional `@Transactional` commits and `AfterCommitExecutor` broadcasts.
2. **Social Graph, Relationships, & Group Lifecycle** (Friend Requests, Connection Acceptance, Group Invitations, Direct Member Additions, Member Removals, Role Changes, User Blocks) are implemented **exclusively as REST mutations** with **ZERO WebSocket event broadcasting** to affected counter-parties.
3. **Frontend State Synchronization** relies heavily on local optimistic mutations for the *actor* performing the action, leaving the *target/peer* with stale state until a manual page refresh, logout/login, or application restart.
4. **WebSocket Reconnect Recovery** contains a critical state-machine bug in [`App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1482-L1505) where `prevWsStatusRef` captures the intermediate `'CONNECTING'` state, permanently preventing the reconnect handler `(prev === 'DISCONNECTED' && status === 'CONNECTED')` from executing when the socket reconnects.

### 1.2 The Architectural Guiding Principle
To preserve security, atomic consistency, database constraints, and cryptographic integrity (E2EE), ConnectX **must adhere to the Authoritative REST Mutation + Reactive Domain Event Model**:

```
                                  React Client (Actor)
                                           │
                                           │ 1. HTTP REST Mutation (POST/PUT/DELETE)
                                           ▼
                                 Spring REST Controller
                                           │
                                           │ 2. Authoritative Business Logic & Validation
                                           ▼
                                    Spring Service
                                           │
                                           │ 3. Database Transaction (Atomic Commit)
                                           ▼
                                     MySQL Database
                                           │
                                           │ 4. Transaction Committed (AfterCommitExecutor)
                                           ▼
                                 Domain Event Publisher
                                           │
                                           │ 5. Targeted WebSocket Broadcast (/queue/messages)
                                           ▼
                       ┌───────────────────┴───────────────────┐
                       ▼                                       ▼
            React Client (Peer / B)                 React Client (Actor / A)
                       │                                       │
                       │ 6. Reactive State Update              │ 6. Confirmation / Reconcile
                       ▼                                       ▼
                  Updated UI                              Updated UI
```

*Exceptions*:
- **Ephemeral Signals** (Typing indicators): WebSocket only (`/app/typing` → `/queue/messages`), no DB persistence.
- **E2EE Real-time Text Send**: Kept on existing `@MessageMapping("/message.send")` pipeline to prevent regressions to working chat paths.
- **Bulk Data / File Transfer / Auth**: REST only (Login, Search, Pagination, Media Upload/Download).

---

## 2. Current REST Architecture

Every endpoint below has been verified against the current backend source code.

### 2.1 Authentication Subsystem (`com.connectx.auth`)
*Controller*: [`AuthController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/auth/controller/AuthController.java) | *Service*: [`AuthService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/auth/service/AuthService.java)

| Method | Path | DB Mutation? | Current WS Event? | Frontend Consumer | Authoritative Purpose | Should Remain REST? | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Yes (`users`, `devices`) | None | `authApi.register()` | Create new user account and initial device | **Yes** (REST-Only) | CONFIRMED BY CODE |
| `POST` | `/api/v1/auth/login` | Yes (`devices.last_seen_at`) | None | `authApi.login()` | Authenticate credentials, issue JWT tokens | **Yes** (REST-Only) | CONFIRMED BY CODE |
| `POST` | `/api/v1/auth/logout` | No (JWT stateless) | None | `authApi.logout()` | Invalidate client session, trigger local cleanup | **Yes** (REST-Only) | CONFIRMED BY CODE |
| `POST` | `/api/v1/auth/refresh` | No | None | `apiClient.ts` interceptor | Rotate expired JWT access token | **Yes** (REST-Only) | CONFIRMED BY CODE |
| `POST` | `/api/v1/auth/forgot-password/request-otp` | Yes (`password_reset_otps`) | None | `authApi.requestForgotPasswordOtp()` | Generate and send password reset OTP | **Yes** (REST-Only) | CONFIRMED BY CODE |
| `POST` | `/api/v1/auth/forgot-password/verify-otp` | Yes (`password_reset_otps`) | None | `authApi.verifyForgotPasswordOtp()` | Verify validity of reset OTP | **Yes** (REST-Only) | CONFIRMED BY CODE |
| `POST` | `/api/v1/auth/forgot-password/reset-password`| Yes (`users.password_hash`) | None | `authApi.resetPasswordWithOtp()` | Update user credentials after OTP check | **Yes** (REST-Only) | CONFIRMED BY CODE |

---

### 2.2 Connections & Friend Requests (`com.connectx.connection`)
*Controller*: [`ConnectionController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/controller/ConnectionController.java) | *Service*: [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java)

| Method | Path | DB Mutation? | Current WS Event? | Frontend Consumer | Authoritative Purpose | Should Remain REST? | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/connections/requests` | Yes (`connection_requests` INSERT) | **NONE (BUG)** | `connectionApi.sendRequest()` | Create PENDING connection request | **Both (REST + WS)** | CONFIRMED BY CODE |
| `GET` | `/api/v1/connections/requests/pending` | No | None | `connectionApi.getPendingIncoming()` | Retrieve incoming pending requests | **Yes** (REST query) | CONFIRMED BY CODE |
| `GET` | `/api/v1/connections/requests/sent` | No | None | `connectionApi.getSentOutgoing()` | Retrieve outgoing pending requests | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/connections/requests/{id}/accept` | Yes (`connection_requests` UPDATE, `connections` INSERT) | **NONE (BUG)** | `connectionApi.acceptRequest()` | Transition request to ACCEPTED, form mutual connection | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/connections/requests/{id}/reject` | Yes (`connection_requests` UPDATE) | **NONE (BUG)** | `connectionApi.rejectRequest()` | Transition request to REJECTED | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/connections/requests/{id}/cancel` | Yes (`connection_requests` UPDATE) | **NONE (BUG)** | `connectionApi.cancelRequest()` | Transition request to CANCELLED | **Both (REST + WS)** | CONFIRMED BY CODE |
| `GET` | `/api/v1/connections` | No | None | `connectionApi.getConnections()` | List all active mutual connections | **Yes** (REST query) | CONFIRMED BY CODE |
| `DELETE` | `/api/v1/connections/{userId}` | Yes (`connections` DELETE) | **NONE (BUG)** | `connectionApi.removeConnection()` | Terminate mutual connection | **Both (REST + WS)** | CONFIRMED BY CODE |

---

### 2.3 Groups Subsystem (`com.connectx.group`)
*Controllers*: [`GroupController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/controller/GroupController.java), [`GroupInvitationController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/controller/GroupInvitationController.java), [`GroupMembershipController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/controller/GroupMembershipController.java), [`GroupKeyController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/controller/GroupKeyController.java), [`GroupImageController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/controller/GroupImageController.java)  
*Services*: [`GroupService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java), [`GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java), [`GroupKeyService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupKeyService.java)

| Method | Path | DB Mutation? | Current WS Event? | Frontend Consumer | Authoritative Purpose | Should Remain REST? | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/groups` | Yes (`conversations`, `chat_groups`, `conversation_members`) | None | `groupApi.createGroup()` | Create new group conversation and assign owner | **Yes** (Caller gets DTO, no peer to notify yet) | CONFIRMED BY CODE |
| `GET` | `/api/v1/groups/{groupId}` | No | None | `groupApi.getGroup()` | Get group metadata and policies | **Yes** (REST query) | CONFIRMED BY CODE |
| `GET` | `/api/v1/groups/{groupId}/members` | No | None | `groupApi.getGroupMembers()` | List active members and roles | **Yes** (REST query) | CONFIRMED BY CODE |
| `PATCH` | `/api/v1/groups/{groupId}/settings` | Yes (`chat_groups` UPDATE) | `GROUP_INFO_UPDATED` | `groupApi.updateGroupSettings()` | Update permissions/policies | **Both (REST + WS)** | CONFIRMED BY CODE |
| `PATCH` | `/api/v1/groups/{groupId}/info` | Yes (`chat_groups` UPDATE) | `GROUP_INFO_UPDATED` | `groupApi.updateGroupInfo()` | Update group name/description | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/{groupId}/avatar` | Yes (`chat_groups` UPDATE) | `GROUP_INFO_UPDATED` | `groupApi.uploadAvatar()` | Upload and link group photo | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/groups/{groupId}/avatar` | Yes (`chat_groups` UPDATE) | `GROUP_INFO_UPDATED` | `groupApi.removeAvatar()` | Remove group photo | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/groups/{groupId}` | Yes (`conversation_members` soft delete) | `CONVERSATION_DELETED` | `groupApi.deleteGroup()` | Soft-delete group for all members | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/{groupId}/invitations` | Yes (`group_invitations` INSERT or `conversation_members` INSERT) | `GROUP_KEY_ROTATION_REQUIRED` (if direct-add only) | `groupApi.createInvitation()` | Direct-add member OR create pending invitation | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/invitations/{id}/accept` | Yes (`group_invitations` UPDATE, `conversation_members` INSERT/restore) | `GROUP_KEY_ROTATION_REQUIRED` | `groupApi.acceptInvitation()` | Accept invitation and join group | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/invitations/{id}/reject` | Yes (`group_invitations` UPDATE) | **NONE (BUG)** | `groupApi.rejectInvitation()` | Reject pending invitation | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/invitations/{id}/cancel` | Yes (`group_invitations` UPDATE) | **NONE (BUG)** | `groupApi.cancelInvitation()` | Inviter cancels pending invitation | **Both (REST + WS)** | CONFIRMED BY CODE |
| `GET` | `/api/v1/groups/invitations/received` | No | None | `groupApi.getReceivedInvitations()` | List pending received invitations | **Yes** (REST query) | CONFIRMED BY CODE |
| `GET` | `/api/v1/groups/invitations/sent` | No | None | `groupApi.getSentInvitations()` | List pending sent invitations | **Yes** (REST query) | CONFIRMED BY CODE |
| `PATCH` | `/api/v1/groups/{groupId}/members/{userId}/role` | Yes (`conversation_members` UPDATE) | **NONE (BUG)** | `groupApi.changeMemberRole()` | Promote/demote member role | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/groups/{groupId}/members/{userId}` | Yes (`conversation_members` soft delete) | `GROUP_KEY_ROTATION_REQUIRED` (to active only) | `groupApi.removeMember()` | Admin removes member from group | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/{groupId}/leave` | Yes (`conversation_members` soft delete) | `GROUP_KEY_ROTATION_REQUIRED` (to active only) | `groupApi.leaveGroup()` | Member voluntarily leaves group | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/{groupId}/ownership/transfer` | Yes (`conversation_members` UPDATE x2) | **NONE (BUG)** | `groupApi.transferOwnership()` | Transfer OWNER role to another member | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/groups/{groupId}/keys` | Yes (`group_member_keys` INSERT/UPDATE) | None | `groupApi.submitWrappedKey()` | Submit encrypted shared key for member | **Yes** (REST mutation) | CONFIRMED BY CODE |
| `GET` | `/api/v1/groups/{groupId}/keys/me` | No | None | `groupApi.getMyWrappedKey()` | Fetch caller's wrapped group key | **Yes** (REST query) | CONFIRMED BY CODE |
| `GET` | `/api/v1/group-images/{groupId}` | No | None | `<img src="...">` | Stream raw avatar image file | **Yes** (REST stream) | CONFIRMED BY CODE |

---

### 2.4 Conversations Subsystem (`com.connectx.conversation`)
*Controller*: [`ConversationController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/conversation/controller/ConversationController.java) | *Service*: [`ConversationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/conversation/service/ConversationService.java)

| Method | Path | DB Mutation? | Current WS Event? | Frontend Consumer | Authoritative Purpose | Should Remain REST? | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/conversations` | No | None | `conversationApi.getConversations()` | List user's active conversations | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/direct` | Yes (if new: `conversations`, `conversation_members`) | `CONVERSATION_RESTORED` (if self-restoring only) | `conversationApi.createOrGetDirectConversation()` | Find or create 1:1 conversation | **Both (REST + WS)** | CONFIRMED BY CODE |
| `GET` | `/api/v1/conversations/{id}` | No | None | `conversationApi.getConversationById()` | Get single conversation details | **Yes** (REST query) | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/conversations/{id}` | Yes (`conversation_members.deleted_at`, message cleanup) | `CONVERSATION_DELETED` (to caller only) | `conversationApi.deleteConversation()` | Soft delete / hide conversation for user | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/clear` | Yes (`conversation_members.cleared_at`) | `CONVERSATION_CLEARED` (to caller only) | `conversationApi.clearConversation()` | Clear message history for caller | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/pin` | Yes (`conversation_members.is_pinned`) | None | `conversationApi.pinConversation()` | Set pin state (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/unpin` | Yes (`conversation_members.is_pinned`) | None | `conversationApi.unpinConversation()` | Unset pin state (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/mute` | Yes (`conversation_members.muted_until`) | None | `conversationApi.muteConversation()` | Set mute state (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/unmute` | Yes (`conversation_members.muted_until`) | None | `conversationApi.unmuteConversation()` | Unset mute state (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/archive` | Yes (`conversation_members.is_archived`) | None | `conversationApi.archiveConversation()` | Archive conversation (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/unarchive` | Yes (`conversation_members.is_archived`) | None | `conversationApi.unarchiveConversation()` | Unarchive conversation (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/mark-unread`| Yes (`conversation_members.marked_unread`) | None | `conversationApi.markUnread()` | Flag conversation unread (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/mark-read` | Yes (`conversation_members.marked_unread`) | None | `conversationApi.markRead()` | Clear unread flag (caller preference) | **Yes** (Per-user preference) | CONFIRMED BY CODE |

---

### 2.5 Messaging & Media Subsystem (`com.connectx.message`, `com.connectx.media`)
*Controllers*: [`MessageController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/message/controller/MessageController.java), [`MediaController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/media/controller/MediaController.java)  
*Services*: [`MessageService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/message/service/MessageService.java), [`MediaService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/media/service/MediaService.java)

| Method | Path | DB Mutation? | Current WS Event? | Frontend Consumer | Authoritative Purpose | Should Remain REST? | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/conversations/{id}/messages` | No | None | `messageApi.getMessages()` | Paginated message history retrieval | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/messages` | Yes (`messages` INSERT) | `MESSAGE_RECEIVED` | `messageApi.sendMessage()` (fallback) | REST fallback message send | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/messages/{id}` | Yes (`messages` UPDATE / `message_user_states`) | `MESSAGE_DELETED`, `MESSAGE_UNPINNED` | `messageApi.deleteMessage()` | Soft delete for self or everyone | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/messages/{id}/reactions` | Yes (`message_reactions` INSERT/UPDATE) | `MESSAGE_REACTION_UPDATE` | `messageApi.addOrUpdateReaction()` | Add/update emoji reaction | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/messages/{id}/reactions` | Yes (`message_reactions` DELETE) | `MESSAGE_REACTION_UPDATE` | `messageApi.removeReaction()` | Remove emoji reaction | **Both (REST + WS)** | CONFIRMED BY CODE |
| `PUT` | `/api/v1/messages/{id}` | Yes (`messages` UPDATE) | `MESSAGE_EDITED` | `messageApi.editMessage()` | Edit ciphertext within 15-min window | **Both (REST + WS)** | CONFIRMED BY CODE |
| `POST` | `/api/v1/messages/{id}/pin` | Yes (`messages` UPDATE) | `MESSAGE_PINNED` | `messageApi.pinMessage()` | Pin message in conversation | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/messages/{id}/pin` | Yes (`messages` UPDATE) | `MESSAGE_UNPINNED` | `messageApi.unpinMessage()` | Unpin message in conversation | **Both (REST + WS)** | CONFIRMED BY CODE |
| `GET` | `/api/v1/conversations/{id}/pinned-message`| No | None | `messageApi.getPinnedMessage()` | Fetch current pinned message | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/messages/{id}/star` | Yes (`message_stars` INSERT) | None | `messageApi.starMessage()` | Star message (personal bookmark) | **Yes** (Personal preference) | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/messages/{id}/star` | Yes (`message_stars` DELETE) | None | `messageApi.unstarMessage()` | Unstar message (personal bookmark) | **Yes** (Personal preference) | CONFIRMED BY CODE |
| `POST` | `/api/v1/conversations/{id}/media` | Yes (`message_media` INSERT) | None | `mediaApi.uploadMedia()` | Upload encrypted binary payload | **Yes** (Binary file upload) | CONFIRMED BY CODE |
| `GET` | `/api/v1/media/{mediaId}` | No | None | `mediaApi.getMediaUrl()` | Download encrypted/raw media binary | **Yes** (Binary file stream) | CONFIRMED BY CODE |

---

### 2.6 User State, Blocks, Devices, & Push
*Controllers*: [`UserController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/user/controller/UserController.java), [`BlockController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/block/controller/BlockController.java), [`DeviceController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/device/controller/DeviceController.java), [`PushNotificationController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/push/controller/PushNotificationController.java)

| Method | Path | DB Mutation? | Current WS Event? | Frontend Consumer | Authoritative Purpose | Should Remain REST? | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/users/search` | No | None | `userApi.searchUsers()` | Find users by username prefix | **Yes** (REST query) | CONFIRMED BY CODE |
| `GET` | `/api/v1/users/me` | No | None | `userApi.getCurrentUser()` | Get authenticated user profile | **Yes** (REST query) | CONFIRMED BY CODE |
| `GET` | `/api/v1/users/{userId}` | No | None | `userApi.getUserById()` | Get public user profile | **Yes** (REST query) | CONFIRMED BY CODE |
| `PATCH` | `/api/v1/users/me` | Yes (`users` UPDATE) | None | `userApi.updateProfile()` | Update bio, display name, privacy | **Yes** (Self profile) | CONFIRMED BY CODE |
| `POST` | `/api/v1/users/me/profile-photo` | Yes (`users.profile_image_url`) | None | `userApi.uploadProfilePhoto()` | Upload profile image file | **Yes** (Binary upload) | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/users/me/profile-photo` | Yes (`users.profile_image_url`) | None | `userApi.removeProfilePhoto()` | Clear profile photo | **Yes** (Self profile) | CONFIRMED BY CODE |
| `GET` | `/api/v1/users/me/identity-key` | No | None | `userApi.getIdentityKey()` | Fetch stored E2EE identity key | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/users/me/identity-key` | Yes (`user_identity_keys`) | None | `userApi.saveIdentityKey()` | Persist E2EE identity bundle | **Yes** (E2EE key setup) | CONFIRMED BY CODE |
| `POST` | `/api/v1/blocks/{userId}` | Yes (`user_blocks` INSERT, `connections` DELETE) | **NONE (BUG)** | `blockApi.blockUser()` | Block user & terminate relationship | **Both (REST + WS)** | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/blocks/{userId}` | Yes (`user_blocks` DELETE) | **NONE (BUG)** | `blockApi.unblockUser()` | Unblock user | **Both (REST + WS)** | CONFIRMED BY CODE |
| `GET` | `/api/v1/blocks` | No | None | `blockApi.getBlocks()` | List blocked users for caller | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/devices` | Yes (`devices` INSERT) | None | `deviceApi.registerDevice()` | Register new client device | **Yes** (REST mutation) | CONFIRMED BY CODE |
| `GET` | `/api/v1/devices` | No | None | `deviceApi.getDevices()` | List caller's registered devices | **Yes** (REST query) | CONFIRMED BY CODE |
| `DELETE`| `/api/v1/devices/{deviceId}` | Yes (`devices` UPDATE) | None | `deviceApi.deactivateDevice()` | Deactivate device | **Yes** (REST mutation) | CONFIRMED BY CODE |
| `POST` | `/api/v1/devices/{deviceId}/seen` | Yes (`devices` UPDATE) | None | `deviceApi.markDeviceSeen()` | Update device heartbeat | **Yes** (REST mutation) | CONFIRMED BY CODE |
| `GET` | `/api/v1/users/{userId}/devices/public-keys`| No | None | `deviceApi.getUserPublicKeys()` | Fetch target user's device keys | **Yes** (E2EE key lookup) | CONFIRMED BY CODE |
| `GET` | `/api/v1/push/vapid-public-key`| No | None | `pushSubscription.ts` | Retrieve server VAPID key | **Yes** (REST query) | CONFIRMED BY CODE |
| `POST` | `/api/v1/push/subscribe` | Yes (`push_subscriptions`) | None | `pushSubscription.ts` | Register Web Push endpoint | **Yes** (REST mutation) | CONFIRMED BY CODE |
| `POST` | `/api/v1/push/unsubscribe` | Yes (`push_subscriptions` DELETE) | None | `pushSubscription.ts` | Remove Web Push endpoint | **Yes** (REST mutation) | CONFIRMED BY CODE |

---

## 3. Current WebSocket Architecture

### 3.1 Connection Handshake & Channel Configuration
- **STOMP Endpoint**: `/ws` (with SockJS fallback configured in [`WebSocketConfig.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/config/WebSocketConfig.java#L31-L37)).
- **Authentication**: Intercepted at STOMP `CONNECT` frame via [`WebSocketAuthChannelInterceptor.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/config/WebSocketAuthChannelInterceptor.java#L50-L58). Reads `Authorization: Bearer <token>` or `token` native header, decodes JWT, and populates `accessor.setUser(UsernamePasswordAuthenticationToken)`.
- **Subscription Authorization**: Intercepted at STOMP `SUBSCRIBE` frame in [`WebSocketAuthChannelInterceptor.java:L65-L92`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/config/WebSocketAuthChannelInterceptor.java#L65-L92). Only checks `/topic/conversation/{conversationId}` to ensure caller is an active member (`deletedAt IS NULL`). User destinations (`/user/queue/*`) are automatically scoped by Spring Security.
- **Broker Prefixes**:
  - Application Inbound: `/app`
  - User Destinations: `/user`
  - Simple Broker Outbound: `/topic`, `/queue`

---

### 3.2 Inbound Message Mappings (`@MessageMapping`)
Controller: [`WebSocketMessageController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/websocket/controller/WebSocketMessageController.java)

| Inbound Destination | Method | Payload Contract | DB Mutation? | Outbound Response / Fanout | Classification |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `/app/message.send` | `handleSendMessage` | `WsEvent` containing `conversationId`, `ciphertext`, `nonce`, `encryptionAlgorithm`, `groupKeyVersion`, `replyToMessageId`, `clientTempId` | Yes (`MessageService.sendMessage`) | `/user/{sender}/queue/acks` (`MESSAGE_ACK`) + `/queue/messages` / `/topic/conversation/{id}` (`MESSAGE_RECEIVED`) | CONFIRMED BY CODE |
| `/app/message.delivered` | `handleMessageDelivered`| `WsEvent` containing `messageId` | Yes (`MessageService.markDelivered`) | None emitted directly (relies on read/delivered polling or sender query) | CONFIRMED BY CODE |
| `/app/message.read` | `handleMessageRead` | `WsEvent` containing `conversationId`, `maxMessageId` / `upToMessageId` or `messageId` | Yes (`MessageService.markConversationAsRead`) | `/user/{sender}/queue/messages` (`READ_RECEIPT_UPDATE`) | CONFIRMED BY CODE |
| `/app/typing` | `handleTyping` | `WsEvent` containing `conversationId`, `isTyping` | **No (Ephemeral)** | `/user/{otherMember}/queue/messages` (`TYPING_INDICATOR`) | CONFIRMED BY CODE |

---

### 3.3 Complete Outbound WebSocket Event Table

| EVENT TYPE | PUBLISHER (Backend File) | DESTINATION | RECIPIENT | PAYLOAD DTO | FRONTEND HANDLER (`App.tsx`) | REACT STATE UPDATED | UI UPDATED | CURRENT STATUS |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `MESSAGE_RECEIVED` | `MessageService.java:L417-L422` | `/topic/conversation/{id}` & `/user/{user}/queue/messages` | Active members of DIRECT or GROUP | `messageId`, `conversationId`, `ciphertext`, `nonce`, `mediaId`, `sentAt`, `groupKeyVersion`, `clientTempId` | [`App.tsx:L1566-L1813`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1566) | `messages`, `conversationCache`, `conversationPreviews`, `unreadConversationIds` | Chat stream, Sidebar preview & badge, Toast, System notification | ✅ **Correct** |
| `MESSAGE_ACK` | `WebSocketMessageController.java:L83-L86` | `/user/{sender}/queue/acks` | Message sender only | `messageId`, `conversationId`, `sentAt`, `clientTempId` | [`App.tsx:L1983-L2020`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1983) | `messages` (matches negative temp ID -> real ID, status: `'SENT'`) | Sent tick icon | ✅ **Correct** |
| `MESSAGE_EDITED` | `MessageService.java:L661-L671` | `/topic/conversation/{id}` & `/user/{user}/queue/messages` | All conversation members | `messageId`, `conversationId`, `ciphertext`, `nonce`, `editedAt` | [`App.tsx:L1837-L1871`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1837) | `messages`, `conversationCache`, `pinnedMessage` | Message content, (edited) label | ✅ **Correct** |
| `MESSAGE_DELETED` | `MessageService.java:L591-L606` | `/topic/conversation/{id}` & `/user/{user}/queue/messages` | All conversation members | `messageId`, `conversationId`, `deletedForEveryone` | [`App.tsx:L1872-L1893`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1872) | `messages`, `conversationCache`, `pinnedMessage` | "This message was deleted" placeholder | ✅ **Correct** |
| `MESSAGE_PINNED` / `MESSAGE_UNPINNED` | `MessageService.java:L716-L726` | `/topic/conversation/{id}` & `/user/{user}/queue/messages` | All conversation members | `messageId`, `conversationId`, `pinnedAt`, `pinnedByUserId`, `pinnedByUsername` | [`App.tsx:L1894-L1928`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1894) | `messages`, `conversationCache`, `pinnedMessage` | Header pinned message bar, pin badge | ✅ **Correct** |
| `READ_RECEIPT_UPDATE` | `MessageService.java:L805, L842, L895` | `/user/{sender}/queue/messages` | Original message sender | `messageId` / `messageIds`, `conversationId`, `deliveredAt`, `readAt` | [`App.tsx:L1929-L1982`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1929) | `messages`, `conversationCache` | Double blue ticks | ✅ **Correct** |
| `MESSAGE_REACTION_UPDATE` | `MessageService.java:L1030, L1080` | `/topic/conversation/{id}` & `/user/{user}/queue/messages` | All conversation members | `messageId`, `conversationId`, `reactions` map | [`App.tsx:L1814-L1836`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1814) | `messages`, `conversationCache` | Reaction chips below message | ✅ **Correct** |
| `TYPING_INDICATOR` | `WebSocketMessageController.java:L205-L212` | `/user/{recipient}/queue/messages` | Other conversation members | `conversationId`, `senderUserId`, `senderUsername`, `isTyping` | [`App.tsx:L2102-L2130`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2102) | `typingByConversation` | "username is typing..." footer | ✅ **Correct** |
| `PRESENCE_UPDATE` | `PresenceService.java:L134-L140` | `/user/{peer}/queue/messages` | Users sharing any conversation with target | `userId`, `status` ('ONLINE'/'OFFLINE'), `lastSeenAt` | [`App.tsx:L2131-L2145`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2131) | `conversations`, `activeConversation` | Green dot, "Online" / "Last seen" subtext | ✅ **Correct** |
| `GROUP_INFO_UPDATED` | `GroupService.java:L226-L230` | `/user/{member}/queue/messages` | Currently active group members | `conversationId` | [`App.tsx:L2074-L2086`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2074) | `groupInfoById`, `conversations` | Header title, group description, policy permissions | ✅ **Correct** |
| `GROUP_KEY_ROTATION_REQUIRED` | `GroupService.java:L461-L466` | `/user/{member}/queue/messages` | Currently active group members | `conversationId`, `keyVersion` | [`App.tsx:L2053-L2073`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2053) | `groupKeyManager`, `groupInfoById` | Triggers key resolution / cache invalidation | ✅ **Correct** |
| `CONVERSATION_RESTORED` | `ConversationService.java:L121`, `MessageService.java:L399` | `/user/{user}/queue/messages` | Restored user | `conversationId` | [`App.tsx:L2021-L2031`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2021) | `conversations`, `messages` | Unhides hidden conversation in sidebar | ✅ **Correct** |
| `CONVERSATION_DELETED` | `GroupService.java:L390`, `ConversationService.java:L443` | `/topic/conversation/{id}` & `/user/{user}/queue/messages` | Group members / deleting direct user | `conversationId`, `deletedByUserId` | [`App.tsx:L2032-L2052`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2032) | `conversations`, `conversationCache`, `activeConversation` | Closes chat screen, removes from sidebar | ✅ **Correct** |
| `CONVERSATION_CLEARED` | `ConversationService.java:L470` | `/user/{user}/queue/messages` | Clearing user only | `conversationId`, `clearedAt` | [`App.tsx:L2087-L2101`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2087) | `messages`, `conversationPreviews` | Clears chat window for current user | ✅ **Correct** |
| *Friend Request Events* | **MISSING IN BACKEND** | — | Target user / Requester | — | **MISSING IN FRONTEND** | `receivedRequestsByUserId`, `sentRequestsByUserId` | Request badge, incoming request modal | ❌ **Missing** |
| *Connection Accepted Events* | **MISSING IN BACKEND** | — | Requester / Target user | — | **MISSING IN FRONTEND** | `connectedUserIds`, `sentRequestsByUserId` | Relationship status -> CONNECTED, enables Chat | ❌ **Missing** |
| *Connection Removed / Block Events* | **MISSING IN BACKEND** | — | Other user | — | **MISSING IN FRONTEND** | `connectedUserIds`, `blockedUserIds` | Relationship status -> NOT_CONNECTED / BLOCKED | ❌ **Missing** |
| *Group Invitation Events* | **MISSING IN BACKEND** | — | Invitee / Inviter | — | **MISSING IN FRONTEND** | `receivedGroupInvitations`, `sentGroupInvitations` | Group invitation badge, modal list | ❌ **Missing** |
| *Group Member Added / Removed Events* | **MISSING IN BACKEND** | — | Target member & all group members | — | **MISSING IN FRONTEND** | `groupMembersById`, `conversations`, `activeConversation` | Sidebar group list, member roster | ❌ **Missing** |
| *Group Role Changed Events* | **MISSING IN BACKEND** | — | Target member & all group members | — | **MISSING IN FRONTEND** | `groupMembersById` | Admin badges, composer/settings permissions | ❌ **Missing** |

---

## 4. REST vs WebSocket Decision Matrix

| Operation | REST | WebSocket | Both | Reason |
| :--- | :---: | :---: | :---: | :--- |
| **Friend Request Sent** | | | **Both** | **REST is authoritative** (validates blocking, duplicate checks, creates DB row in transaction). **WebSocket is notification** (`FRIEND_REQUEST_RECEIVED`) to recipient to increment badge and append to incoming requests list in real time without polling. |
| **Friend Request Accepted** | | | **Both** | **REST is authoritative** (updates request to ACCEPTED, inserts `connections` row under DB unique constraint). **WebSocket is notification** (`CONNECTION_ACCEPTED`) to requester and recipient to transition relationship status to `CONNECTED` and unlock direct messaging. |
| **Friend Request Rejected** | | | **Both** | **REST is authoritative** (marks REJECTED in DB). **WebSocket is notification** (`FRIEND_REQUEST_REJECTED`) to requester to clear pending sent state. |
| **Friend Request Cancelled** | | | **Both** | **REST is authoritative** (marks CANCELLED in DB). **WebSocket is notification** (`FRIEND_REQUEST_CANCELLED`) to recipient to remove pending incoming item. |
| **Connection Removed** | | | **Both** | **REST is authoritative** (deletes `connections` row in DB). **WebSocket is notification** (`CONNECTION_REMOVED`) to other user to transition relationship status to `NOT_CONNECTED`. |
| **User Blocked** | | | **Both** | **REST is authoritative** (inserts `user_blocks`, terminates connection and pending requests). **WebSocket is notification** (`USER_BLOCKED`) to peer to invalidate connection state and gate messaging. |
| **User Unblocked** | | | **Both** | **REST is authoritative** (deletes `user_blocks` row). **WebSocket is notification** (`USER_UNBLOCKED`) to update relationship state. |
| **Group Invitation Sent** | | | **Both** | **REST is authoritative** (validates membership, role policies, privacy, creates `group_invitations` row). **WebSocket is notification** (`GROUP_INVITATION_RECEIVED`) to invitee to update invitation badge and modal. |
| **Group Invitation Accepted** | | | **Both** | **REST is authoritative** (validates capacity under pessimistic lock, marks ACCEPTED, creates membership, increments key version). **WebSocket is notification** (`GROUP_INVITATION_ACCEPTED` / `GROUP_MEMBER_JOINED`) to group members and inviter. |
| **Group Invitation Rejected / Cancelled** | | | **Both** | **REST is authoritative** (updates invitation status in DB). **WebSocket is notification** (`GROUP_INVITATION_REJECTED` / `GROUP_INVITATION_CANCELLED`) to inviter / invitee. |
| **Group Direct Member Added** | | | **Both** | **REST is authoritative** (validates consent/privacy, creates active membership). **WebSocket is notification** (`GROUP_MEMBER_ADDED` to newly added user with conversation DTO so group appears in their sidebar immediately; `GROUP_MEMBER_JOINED` to existing members). |
| **Group Member Removed** | | | **Both** | **REST is authoritative** (soft deletes membership row, rotates key version). **WebSocket is notification** (`GROUP_MEMBER_REMOVED` to removed member so group is removed from sidebar and chat closed; `GROUP_MEMBER_LEFT` to remaining members). |
| **Group Member Leaves** | | | **Both** | **REST is authoritative** (soft deletes membership, rotates key version). **WebSocket is notification** (`GROUP_MEMBER_LEFT` to remaining members). |
| **Group Role Changed** | | | **Both** | **REST is authoritative** (updates `role` column in DB). **WebSocket is notification** (`GROUP_ROLE_CHANGED`) to all members to update admin badges and permissions. |
| **Group Info / Avatar / Settings Updated** | | | **Both** | **REST is authoritative** (validates policy, updates `chat_groups`). **WebSocket is notification** (existing `GROUP_INFO_UPDATED`) to refresh group metadata for active members. |
| **Group Deleted** | | | **Both** | **REST is authoritative** (owner bulk soft-deletes active memberships). **WebSocket is notification** (existing `CONVERSATION_DELETED`) to close group view and remove from sidebar for all members. |
| **Direct Conversation Created** | | | **Both** | **REST is authoritative** (creates `conversations` + `conversation_members` under lock). **WebSocket is notification** (`CONVERSATION_CREATED`) to target user if created before first message. |
| **Message Sent (E2EE Text)** | | **WS** | | **WebSocket is authoritative and realtime** (existing working flow: `@MessageMapping("/message.send")` -> `MessageService.sendMessage` in DB -> `MESSAGE_ACK` + `MESSAGE_RECEIVED`). Must remain untouched to avoid regression. |
| **Message Sent (Media Fallback / Document)** | | | **Both** | **REST is authoritative** (handles multipart upload, saves metadata). **WebSocket is notification** (`MESSAGE_RECEIVED`) to conversation members. |
| **Message Edit / Delete / Reaction / Pin** | | | **Both** | **REST is authoritative** (validates edit window / ownership, updates DB). **WebSocket is notification** (`MESSAGE_EDITED`, `MESSAGE_DELETED`, `MESSAGE_REACTION_UPDATE`, `MESSAGE_PINNED`) to all members. |
| **Read Receipts / Delivery Ack** | | **WS** | | **WebSocket only** (`@MessageMapping("/message.read")` and `/message.delivered` directly fan out `READ_RECEIPT_UPDATE`). High throughput, low latency. |
| **Typing Indicator** | | **WS** | | **WebSocket only** (ephemeral, zero DB persistence, synchronous fanout). |
| **Online Presence** | | **WS** | | **WebSocket only** (session connected/disconnected lifecycle listener -> DB status update -> `PRESENCE_UPDATE` fanout). |
| **User Login / Register / Refresh / OTP** | **REST** | | | **REST only is sufficient** (stateless auth, session establishment, credential hashing; WebSocket cannot exist before auth). |
| **Search Users / Fetch History / Keys** | **REST** | | | **REST only is sufficient** (read-only queries, pagination, on-demand cryptographic key bundle downloads). |
| **Media Binary Download** | **REST** | | | **REST only is sufficient** (binary streaming, HTTP caching headers, chunked transfer). |

---

## 5. Current Refresh/Restart Problems (Forensic Analysis)

### Bug 1: Friend Request Sent (A sends to B)
- **Code Trace**:
  - Actor: `App.tsx` calls `handleSendConnectionRequest(userId)` -> `connectionApi.sendRequest(userId)`.
  - Backend: [`ConnectionService.java:L64-L109`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L64-L109) validates and saves `ConnectionRequest` with status `PENDING`.
  - Backend WebSocket: **Zero calls to `messagingTemplate` or `WsEvent`**.
- **Current Behavior**:
  - User A's frontend updates `sentRequestsByUserId` locally. User A sees "Request Sent".
  - User B receives **no WebSocket event**, **no notification badge increment**, **no toast**, and **no item in incoming requests list**.
- **Expected Behavior**:
  - User B immediately receives a real-time `FRIEND_REQUEST_RECEIVED` event on `/user/{username}/queue/messages`.
  - User B's state updates `receivedRequestsByUserId`, request badge counter increments, and toast/notification sounds.
- **Missing Event**: `FRIEND_REQUEST_RECEIVED` (Payload: `ConnectionRequestDto`).

---

### Bug 2: Connection Accepted (B accepts A)
- **Code Trace**:
  - Actor: `App.tsx` calls `handleAcceptConnectionRequest(requestId, requesterUserId)` -> `connectionApi.acceptRequest(requestId)`.
  - Backend: [`ConnectionService.java:L135-L175`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L135-L175) sets request status `ACCEPTED` and inserts `UserConnection` row in MySQL.
  - Backend WebSocket: **Zero calls to `messagingTemplate`**.
- **Current Behavior**:
  - User B's frontend adds User A to `connectedUserIds` and deletes the pending request locally.
  - User A's frontend receives **no event**. User A's `connectedUserIds` still lacks User B, and `sentRequestsByUserId` still holds the pending request. If User A opens the chat with User B, the UI renders the `ChatRelationshipGate` ("You must be connected to send messages") because `getRelationshipStatus()` evaluates to `REQUEST_SENT`. User A is locked out of messaging User B until User A manually refreshes the page!
- **Expected Behavior**:
  - User A immediately receives `CONNECTION_ACCEPTED` over WebSocket.
  - User A's frontend adds User B to `connectedUserIds`, deletes User B from `sentRequestsByUserId`, and unlocks the message composer.
- **Missing Event**: `CONNECTION_ACCEPTED` (Payload: `ConnectionRequestDto` + `connectedUserId`).

---

### Bug 3: Group Invitation Created (A invites B)
- **Code Trace**:
  - Actor: `groupApi.createInvitation(groupId, targetUserId)`.
  - Backend: [`GroupInvitationService.java:L82-L128`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java#L82-L128) creates `GroupInvitation` (status `PENDING`).
  - Backend WebSocket: **Zero calls to `messagingTemplate`**.
- **Current Behavior**:
  - User B receives **nothing**. User B's `receivedGroupInvitations` array remains empty.
  - User B never sees a badge or invitation modal item until page reload.
- **Expected Behavior**:
  - User B receives `GROUP_INVITATION_RECEIVED`.
  - User B's frontend appends invitation to `receivedGroupInvitations`, increments badge, and triggers notification.
- **Missing Event**: `GROUP_INVITATION_RECEIVED` (Payload: `GroupInvitationDto`).

---

### Bug 4: Direct Member Added to Group (A direct-adds B)
- **Code Trace**:
  - Actor: `groupApi.createInvitation(groupId, targetUserId)` -> evaluation decision `DIRECT_ADD`.
  - Backend: [`GroupInvitationService.java:L101-L113`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java#L101-L113) calls `groupService.addMember` and `groupService.markKeyRotationRequired(groupId)`.
  - Backend WebSocket: `markKeyRotationRequired` sends `GROUP_KEY_ROTATION_REQUIRED` to active members.
- **Current Behavior**:
  - User B receives `GROUP_KEY_ROTATION_REQUIRED`, but in [`App.tsx:L2053-L2073`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L2053), this handler only invalidates `groupKeyManager` and re-fetches group details *if the group is already known*. It **does not add the group to `conversations`**!
  - User B does not see the new group in their sidebar until they refresh the browser!
- **Expected Behavior**:
  - User B receives `GROUP_MEMBER_ADDED` carrying the full `ConversationDto` + `GroupDto`.
  - User B's frontend prepends the group to `conversations` and initializes group key resolution.
  - Existing group members receive `GROUP_MEMBER_JOINED` to update their member list cache.
- **Missing Event**: `GROUP_MEMBER_ADDED` (to new member), `GROUP_MEMBER_JOINED` (to existing members).

---

### Bug 5: Group Member Removed (Admin removes B)
- **Code Trace**:
  - Actor: `groupApi.removeMember(groupId, targetUserId)`.
  - Backend: [`GroupService.java:L326-L329`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java#L326-L329) calls `endMembership` -> soft-deletes membership, calls `markKeyRotationRequired(groupId)` for remaining active members.
  - Backend WebSocket: `markKeyRotationRequired` filters recipients to `deletedAt IS NULL`. Removed User B is explicitly excluded and receives **zero events**.
- **Current Behavior**:
  - User B's frontend remains in the group view with the composer active.
  - User B only discovers they were removed when they attempt to send a message and receive an HTTP 403 / WS error.
- **Expected Behavior**:
  - User B receives `GROUP_MEMBER_REMOVED` on their private queue `/user/{username}/queue/messages`.
  - User B's frontend removes the group from `conversations`, invalidates keys, and closes `activeConversation` if open.
  - Remaining members receive `GROUP_MEMBER_LEFT` to update member list.
- **Missing Event**: `GROUP_MEMBER_REMOVED` (to target), `GROUP_MEMBER_LEFT` (to group).

---

### Bug 6: Role Changed (Admin changes Member role)
- **Code Trace**:
  - Actor: `groupApi.changeMemberRole(groupId, userId, role)`.
  - Backend: [`GroupService.java:L298-L308`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java#L298-L308) updates role in DB.
  - Backend WebSocket: **Zero calls to `messagingTemplate`**.
- **Current Behavior**:
  - Neither target member nor group members receive an event. Member permissions and badges remain stale until refresh.
- **Expected Behavior**:
  - All active group members receive `GROUP_ROLE_CHANGED`.
  - Frontend updates `groupMembersById` and caller's permissions immediately.
- **Missing Event**: `GROUP_ROLE_CHANGED` (Payload: `groupId`, `userId`, `newRole`).

---

### Bug 7: User Blocked (A blocks B)
- **Code Trace**:
  - Actor: `blockApi.blockUser(userId)`.
  - Backend: [`BlockService.java:L62-L102`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/block/service/BlockService.java#L62-L102) inserts block, removes connection, cancels pending requests.
  - Backend WebSocket: **Zero calls to `messagingTemplate`**.
- **Current Behavior**:
  - User B's UI still shows User A as connected until page refresh.
- **Expected Behavior**:
  - User B receives `USER_BLOCKED` / `CONNECTION_REMOVED`.
  - User B's `connectedUserIds` drops User A, locking the composer.
- **Missing Event**: `USER_BLOCKED` / `CONNECTION_REMOVED`.

---

## 6. Reconnect Architecture Analysis

### 6.1 The Disconnect → Reconnect Lifecycle

```
                  ┌──────────────────────────────────────────────┐
                  │                 CONNECTED                    │
                  └──────────────────────┬───────────────────────┘
                                         │ Socket drop / Network loss
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │                DISCONNECTED                  │
                  │        (scheduleReconnect backoff)           │
                  └──────────────────────┬───────────────────────┘
                                         │ Backoff timer expires -> connect()
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │                 CONNECTING                   │
                  │    (prevWsStatusRef.current = 'CONNECTING')  │
                  └──────────────────────┬───────────────────────┘
                                         │ STOMP onConnect callback
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │                 CONNECTED                    │
                  │  BUG: prev is 'CONNECTING', not 'DISCONNECTED'│
                  │   => RECONCILIATION NEVER RUNS!              │
                  └──────────────────────────────────────────────┘
```

### 6.2 Confirmation of the Reconnect State-Machine Bug
In [`App.tsx:L1482-L1505`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1482-L1505):
```typescript
// App.tsx
useEffect(() => {
  const prev = prevWsStatusRef.current;
  prevWsStatusRef.current = status;
  // BUG: prev is 'CONNECTING' when status becomes 'CONNECTED'!
  if (prev === 'DISCONNECTED' && status === 'CONNECTED' && currentUser) {
    console.log('[ConnectX] WebSocket reconnected — reloading conversations to catch missed messages.');
    loadConversations();
    // ...
  }
}, [status, currentUser, loadConversations, fetchAndSetMessagesForConversation]);
```

**Root Cause**:
When WebSocket reconnects, `WebSocketClient` transitions: `DISCONNECTED` → `CONNECTING` → `CONNECTED`.
1. When status changes to `CONNECTING`, React renders `App.tsx`, and line 1484 updates `prevWsStatusRef.current = 'CONNECTING'`.
2. When status transitions to `CONNECTED`, the effect fires with `status = 'CONNECTED'`, but `prev = prevWsStatusRef.current = 'CONNECTING'`.
3. The guard `prev === 'DISCONNECTED' && status === 'CONNECTED'` evaluates to **`false`**.
4. **Result**: Reconnect recovery **NEVER fires** upon successful reconnection.

---

### 6.3 Reconnect Data Reconciliation Gap
Even if the status check were fixed (`prev !== 'CONNECTED' && status === 'CONNECTED'`), the current implementation only calls:
- `loadConversations()` (sidebar list metadata)
- `fetchAndSetMessagesForConversation()` (messages of the currently open conversation)

It completely **fails to reconcile**:
- Pending incoming & outgoing connection requests (`loadRelationshipData()`)
- Active connection list (`loadRelationshipData()`)
- Blocked users (`loadRelationshipData()`)
- Group received & sent invitations (`loadGroupInvitations()`)
- Group metadata & active member rosters (`loadGroupInfoById`, `loadGroupMembersById`)
- Unread conversation counters

---

## 7. Event Design & Taxonomy Audit

### 7.1 Existing Backend Event Names
The following event type strings are currently emitted by backend code:
- `MESSAGE_RECEIVED`
- `MESSAGE_ACK`
- `MESSAGE_DELETED`
- `MESSAGE_UNPINNED`
- `MESSAGE_EDITED`
- `MESSAGE_PINNED`
- `READ_RECEIPT_UPDATE`
- `MESSAGE_REACTION_UPDATE`
- `TYPING_INDICATOR`
- `PRESENCE_UPDATE`
- `GROUP_INFO_UPDATED`
- `GROUP_KEY_ROTATION_REQUIRED`
- `CONVERSATION_RESTORED`
- `CONVERSATION_DELETED`
- `CONVERSATION_CLEARED`

### 7.2 Proposed Comprehensive Event Taxonomy
To avoid breaking any existing working handler, the taxonomy preserves all existing names and introduces standardized, scoped event names for missing domains:

```
Connection Events:
  - FRIEND_REQUEST_RECEIVED     (Target: recipient user)
  - FRIEND_REQUEST_ACCEPTED     (Target: requester user)
  - FRIEND_REQUEST_REJECTED     (Target: requester user)
  - FRIEND_REQUEST_CANCELLED    (Target: recipient user)
  - CONNECTION_REMOVED          (Target: both users)
  - USER_BLOCKED                (Target: blocked user / blocker)
  - USER_UNBLOCKED              (Target: blocker)

Group Lifecycle Events:
  - GROUP_INVITATION_RECEIVED   (Target: invitee user)
  - GROUP_INVITATION_ACCEPTED   (Target: inviter user)
  - GROUP_INVITATION_REJECTED   (Target: inviter user)
  - GROUP_INVITATION_CANCELLED  (Target: invitee user)
  - GROUP_MEMBER_ADDED          (Target: added user with ConversationDto)
  - GROUP_MEMBER_JOINED         (Target: existing group members)
  - GROUP_MEMBER_REMOVED        (Target: removed user)
  - GROUP_MEMBER_LEFT           (Target: remaining group members)
  - GROUP_ROLE_CHANGED          (Target: all active group members)
  - GROUP_INFO_UPDATED          (Existing - all active group members)
  - GROUP_KEY_ROTATION_REQUIRED (Existing - all active group members)

Conversation & Messaging Events:
  - CONVERSATION_CREATED        (Target: recipient user for new 1:1 chat)
  - CONVERSATION_DELETED        (Existing)
  - CONVERSATION_RESTORED       (Existing)
  - CONVERSATION_CLEARED        (Existing)
  - MESSAGE_RECEIVED            (Existing)
  - MESSAGE_ACK                 (Existing)
  - MESSAGE_EDITED              (Existing)
  - MESSAGE_DELETED             (Existing)
  - MESSAGE_PINNED              (Existing)
  - MESSAGE_UNPINNED            (Existing)
  - MESSAGE_REACTION_UPDATE     (Existing)
  - READ_RECEIPT_UPDATE         (Existing)
  - TYPING_INDICATOR            (Existing)
  - PRESENCE_UPDATE             (Existing)
```

---

## 8. Frontend State Audit

| State Entity | State Location in React | Initial Load Source | REST Mutation Update | WebSocket Event Update | Missing WS Update | Refresh Required Today? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Conversations List** | `conversations` (`App.tsx`) | `loadConversations()` (`GET /conversations`) | Local prepend on send/create | `CONVERSATION_RESTORED`, `CONVERSATION_DELETED`, `CONVERSATION_CLEARED`, `MESSAGE_RECEIVED` | Missing `CONVERSATION_CREATED`, `GROUP_MEMBER_ADDED`, `GROUP_MEMBER_REMOVED` | **Yes** (when added/removed from group or new 1:1 chat) |
| **Active Messages** | `messages` (`App.tsx`) | `fetchAndSetMessagesForConversation()` | Optimistic temp ID send | `MESSAGE_RECEIVED`, `MESSAGE_ACK`, `MESSAGE_EDITED`, `MESSAGE_DELETED`, `MESSAGE_REACTION_UPDATE`, `READ_RECEIPT_UPDATE` | None (fully covered) | **No** (realtime works) |
| **Connected User IDs**| `connectedUserIds` (`App.tsx`) | `loadRelationshipData()` (`GET /connections`) | Optimistic update in actor handlers | **None** | Missing `FRIEND_REQUEST_ACCEPTED`, `CONNECTION_REMOVED`, `USER_BLOCKED` | **Yes** (peer never updates) |
| **Pending Incoming Requests** | `receivedRequestsByUserId` (`App.tsx`) | `loadRelationshipData()` (`GET /connections/requests/pending`) | Local delete on accept/reject | **None** | Missing `FRIEND_REQUEST_RECEIVED`, `FRIEND_REQUEST_CANCELLED` | **Yes** (recipient never sees request) |
| **Pending Sent Requests** | `sentRequestsByUserId` (`App.tsx`) | `loadRelationshipData()` (`GET /connections/requests/sent`) | Local set on send, delete on cancel | **None** | Missing `FRIEND_REQUEST_ACCEPTED`, `FRIEND_REQUEST_REJECTED` | **Yes** (requester never sees accept/reject) |
| **Blocked User IDs** | `blockedUserIds` (`App.tsx`) | `loadRelationshipData()` (`GET /blocks`) | Local set on block, delete on unblock | **None** | Missing `USER_BLOCKED`, `USER_UNBLOCKED` | **Yes** (peer never updates) |
| **Group Invitations (Received)** | `receivedGroupInvitations` (`App.tsx`)| `loadGroupInvitations()` (`GET /groups/invitations/received`) | Local delete on accept/reject | **None** | Missing `GROUP_INVITATION_RECEIVED`, `GROUP_INVITATION_CANCELLED` | **Yes** (invitee never sees invite) |
| **Group Invitations (Sent)** | `sentGroupInvitations` (`App.tsx`) | `loadGroupInvitations()` (`GET /groups/invitations/sent`) | Local set on invite, delete on cancel | **None** | Missing `GROUP_INVITATION_ACCEPTED`, `GROUP_INVITATION_REJECTED` | **Yes** (inviter never sees resolution) |
| **Group Metadata** | `groupInfoById` (`App.tsx`) | `groupApi.getGroup(id)` | Local update in handlers | `GROUP_INFO_UPDATED`, `GROUP_KEY_ROTATION_REQUIRED` | None | **No** |
| **Group Members** | `groupMembersById` (`App.tsx`) | `groupApi.getGroupMembers(id)` | Local update in actor handlers | **None** | Missing `GROUP_MEMBER_JOINED`, `GROUP_MEMBER_LEFT`, `GROUP_ROLE_CHANGED` | **Yes** (members list stays stale) |
| **Unread Counts** | `unreadConversationIds` (`App.tsx`)| Derived from `conversations.unreadCount` | Local clear on select/read | `MESSAGE_RECEIVED` adds to set | None | **No** |
| **User Presence** | `conversations.members[].user.status` | Initial `conversations` fetch | None | `PRESENCE_UPDATE` patches members | None | **No** |

---

## 9. Race Conditions, Deduplication, & Idempotency

### 9.1 Potential Concurrency Hazards
1. **REST Response + WebSocket Event Race (Double-Apply)**:
   - When User A accepts a request or receives a message, both the HTTP response and the WebSocket event arrive at the client.
   - *Requirement*: All frontend state handlers must be strictly idempotent using entity primary keys (`id`, `userId`, `conversationId`, `messageId`).
2. **Message Dedup Window**:
   - [`App.tsx:L1563-L1581`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx#L1563-L1581) already maintains `processedMessageIdsRef` (bounded set of 2,000 IDs).
3. **Database Unique Constraints**:
   - The backend already protects against concurrent inserts with database unique constraints and `REQUIRES_NEW` self-proxy transactions:
     - `uk_connreq_pending_pair`: prevents duplicate pending friend requests.
     - `uk_connections_pair`: prevents duplicate connections.
     - `uk_group_inv_pending_pair`: prevents duplicate pending group invitations.
     - `uk_user_blocks_pair`: prevents duplicate block rows.

---

## 10. Operations That Must Remain REST-Only

The following operations must **NOT** become WebSocket mutations:
1. **Authentication & Session Management**:
   - `POST /api/v1/auth/register`
   - `POST /api/v1/auth/login`
   - `POST /api/v1/auth/logout`
   - `POST /api/v1/auth/refresh`
   - `POST /api/v1/auth/forgot-password/*`
   *Reason*: Authentication establishes the security principal required for STOMP.
2. **Search & Directory Discovery**:
   - `GET /api/v1/users/search`
   *Reason*: Request-response pattern with dynamic pagination/filtering.
3. **Paginated Historical Data & Bulk Queries**:
   - `GET /api/v1/conversations/{id}/messages`
   - `GET /api/v1/connections`
   - `GET /api/v1/connections/requests/*`
   - `GET /api/v1/groups/invitations/*`
   - `GET /api/v1/blocks`
   - `GET /api/v1/devices`
   *Reason*: High-payload queries that should not clog the real-time event broker.
4. **Binary & Multipart Media Transfer**:
   - `POST /api/v1/conversations/{id}/media`
   - `GET /api/v1/media/{mediaId}`
   - `POST /api/v1/users/me/profile-photo`
   - `GET /api/v1/profile-images/{userId}`
   - `POST /api/v1/groups/{groupId}/avatar`
   - `GET /api/v1/group-images/{groupId}`
   *Reason*: Large binary payloads require HTTP streaming and browser caching.
5. **Personal Client Preferences**:
   - `POST /api/v1/conversations/{id}/pin`, `/unpin`, `/mute`, `/unmute`, `/archive`, `/unarchive`
   - `POST /api/v1/messages/{id}/star`, `/unstar`
   *Reason*: These affect only the calling user and need no broadcast to peers.

---

## 11. Operations Requiring REST + WebSocket Broadcasting

Every operation below must follow the pattern:
`REST Endpoint` → `DB Mutation (in Service)` → `AfterCommitExecutor` → `SimpMessagingTemplate.convertAndSendToUser` → `Frontend WS Handler in App.tsx` → `React State Update`.

### 11.1 Connection & Relationship Operations

#### 1. Send Friend Request
- **REST Endpoint**: `POST /api/v1/connections/requests`
- **DB Mutation**: Inserts `ConnectionRequest` (`PENDING`) in [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L64)
- **Event Publisher**: `ConnectionService`
- **Event Name**: `FRIEND_REQUEST_RECEIVED`
- **Target Recipient**: Recipient username (`/user/{recipientUsername}/queue/messages`)
- **Payload**: `ConnectionRequestDto`
- **Frontend State**: `setReceivedRequestsByUserId(prev => new Map(prev).set(dto.requesterId, dto))`
- **UI Result**: Recipient immediately sees request count badge, incoming request item, and toast notification.

#### 2. Accept Friend Request
- **REST Endpoint**: `POST /api/v1/connections/requests/{id}/accept`
- **DB Mutation**: Sets `status = ACCEPTED`, inserts `UserConnection` in [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L135)
- **Event Publisher**: `ConnectionService`
- **Event Name**: `FRIEND_REQUEST_ACCEPTED`
- **Target Recipient**: Requester username (`/user/{requesterUsername}/queue/messages`)
- **Payload**: `ConnectionRequestDto` + `connectedUserId`
- **Frontend State**:
  - Requester: Adds accepted user to `connectedUserIds`, removes from `sentRequestsByUserId`.
  - Recipient: Already updated via REST response.
- **UI Result**: Requester's button switches to "Message", unlocks direct messaging immediately.

#### 3. Reject Friend Request
- **REST Endpoint**: `POST /api/v1/connections/requests/{id}/reject`
- **DB Mutation**: Sets `status = REJECTED` in [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L183)
- **Event Publisher**: `ConnectionService`
- **Event Name**: `FRIEND_REQUEST_REJECTED`
- **Target Recipient**: Requester username
- **Payload**: `requestId`, `recipientUserId`
- **Frontend State**: Requester removes request from `sentRequestsByUserId`.
- **UI Result**: Requester's button reverts to "Add Friend".

#### 4. Cancel Friend Request
- **REST Endpoint**: `POST /api/v1/connections/requests/{id}/cancel`
- **DB Mutation**: Sets `status = CANCELLED` in [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L201)
- **Event Publisher**: `ConnectionService`
- **Event Name**: `FRIEND_REQUEST_CANCELLED`
- **Target Recipient**: Recipient username
- **Payload**: `requestId`, `requesterUserId`
- **Frontend State**: Recipient removes request from `receivedRequestsByUserId`.
- **UI Result**: Recipient's incoming request badge and list item disappear.

#### 5. Remove Connection
- **REST Endpoint**: `DELETE /api/v1/connections/{userId}`
- **DB Mutation**: Deletes `UserConnection` row in [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java#L233)
- **Event Publisher**: `ConnectionService`
- **Event Name**: `CONNECTION_REMOVED`
- **Target Recipient**: Other user username
- **Payload**: `userId: actorUserId`
- **Frontend State**: Removes actor from `connectedUserIds`.
- **UI Result**: Relationship drops back to `NOT_CONNECTED`, composer gates.

#### 6. Block User
- **REST Endpoint**: `POST /api/v1/blocks/{userId}`
- **DB Mutation**: Inserts `UserBlock`, removes connection, cancels requests in [`BlockService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/block/service/BlockService.java#L62)
- **Event Publisher**: `BlockService`
- **Event Name**: `USER_BLOCKED` + `CONNECTION_REMOVED`
- **Target Recipient**: Target user username
- **Payload**: `blockedByUserId: actorUserId`
- **Frontend State**: Target drops actor from `connectedUserIds`, sets relationship to `BLOCKED`.
- **UI Result**: Composer is immediately disabled with blocked notice.

---

### 11.2 Group Lifecycle Operations

#### 7. Group Invitation Sent
- **REST Endpoint**: `POST /api/v1/groups/{groupId}/invitations`
- **DB Mutation**: Inserts `GroupInvitation` (`PENDING`) in [`GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java#L119)
- **Event Publisher**: `GroupInvitationService`
- **Event Name**: `GROUP_INVITATION_RECEIVED`
- **Target Recipient**: Invitee username
- **Payload**: `GroupInvitationDto`
- **Frontend State**: Invitee appends to `receivedGroupInvitations`.
- **UI Result**: Invitee sees badge and invitation item in modal immediately.

#### 8. Direct Add to Group
- **REST Endpoint**: `POST /api/v1/groups/{groupId}/invitations` (Direct add path)
- **DB Mutation**: Creates `ConversationMember` row in [`GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java#L107)
- **Event Publisher**: `GroupInvitationService` / `GroupService`
- **Event Name**:
  - To newly added member: `GROUP_MEMBER_ADDED` (Payload: `ConversationDto` + `GroupDto`)
  - To existing active members: `GROUP_MEMBER_JOINED` (Payload: `ConversationMemberDto`)
- **Frontend State**:
  - Added user: Prepends conversation to `conversations`, caches `groupInfoById`, initializes key manager.
  - Existing members: Appends member to `groupMembersById[groupId]`.
- **UI Result**: Added member immediately sees group appear in their sidebar!

#### 9. Group Invitation Accepted
- **REST Endpoint**: `POST /api/v1/groups/invitations/{id}/accept`
- **DB Mutation**: Marks `ACCEPTED`, creates/restores membership, rotates key in [`GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java#L162)
- **Event Publisher**: `GroupInvitationService`
- **Event Name**:
  - To inviter: `GROUP_INVITATION_ACCEPTED` (Payload: `invitationId`)
  - To existing group members: `GROUP_MEMBER_JOINED` (Payload: `ConversationMemberDto`)
- **Frontend State**:
  - Inviter: Removes invitation from `sentGroupInvitations`.
  - Group members: Appends member to `groupMembersById[groupId]`.
- **UI Result**: Group member list updates in real time.

#### 10. Group Invitation Rejected / Cancelled
- **REST Endpoint**: `POST /api/v1/groups/invitations/{id}/reject` & `cancel`
- **DB Mutation**: Updates invitation status in [`GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java#L207,L227)
- **Event Publisher**: `GroupInvitationService`
- **Event Name**: `GROUP_INVITATION_REJECTED` (to inviter) / `GROUP_INVITATION_CANCELLED` (to invitee)
- **Target Recipient**: Inviter / Invitee
- **Payload**: `invitationId`, `groupId`
- **Frontend State**: Removes item from respective invitation list.
- **UI Result**: Badge count decrements, modal list updates.

#### 11. Group Member Removed
- **REST Endpoint**: `DELETE /api/v1/groups/{groupId}/members/{userId}`
- **DB Mutation**: Soft-deletes `ConversationMember`, rotates key in [`GroupService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java#L326)
- **Event Publisher**: `GroupService`
- **Event Name**:
  - To removed member: `GROUP_MEMBER_REMOVED` (Payload: `conversationId: groupId`)
  - To remaining members: `GROUP_MEMBER_LEFT` (Payload: `conversationId: groupId`, `userId: targetUserId`)
- **Frontend State**:
  - Removed user: Removes group from `conversations`, cleans up keys, closes active chat.
  - Remaining members: Filters removed member out of `groupMembersById[groupId]`.
- **UI Result**: Removed member is immediately kicked from chat; remaining members see roster update.

#### 12. Group Member Leaves
- **REST Endpoint**: `POST /api/v1/groups/{groupId}/leave`
- **DB Mutation**: Soft-deletes `ConversationMember`, rotates key in [`GroupService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java#L340)
- **Event Publisher**: `GroupService`
- **Event Name**: `GROUP_MEMBER_LEFT` (Payload: `conversationId: groupId`, `userId: actorUserId`)
- **Target Recipient**: Remaining active group members
- **Frontend State**: Remaining members filter user out of `groupMembersById[groupId]`.
- **UI Result**: Member roster updates immediately.

#### 13. Group Role Changed
- **REST Endpoint**: `PATCH /api/v1/groups/{groupId}/members/{userId}/role`
- **DB Mutation**: Updates `role` column in [`GroupService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java#L298)
- **Event Publisher**: `GroupService`
- **Event Name**: `GROUP_ROLE_CHANGED`
- **Target Recipient**: All active group members
- **Payload**: `conversationId: groupId`, `userId: targetUserId`, `newRole: role`
- **Frontend State**: Updates role in `groupMembersById[groupId]`. If target is current user, updates permissions.
- **UI Result**: Admin badges and settings controls update in real time.

---

## 12. Complete Missing WebSocket Events Specification

| Domain | Event Type | Target Channel | Recipient Scope | Payload Fields |
| :--- | :--- | :--- | :--- | :--- |
| **Connection** | `FRIEND_REQUEST_RECEIVED` | `/user/{username}/queue/messages` | Recipient user | `id`, `requesterId`, `requesterUsername`, `requesterDisplayName`, `requesterProfileImageUrl`, `createdAt` |
| **Connection** | `FRIEND_REQUEST_ACCEPTED` | `/user/{username}/queue/messages` | Requester user | `id`, `recipientId`, `connectedUserId`, `status: 'ACCEPTED'`, `respondedAt` |
| **Connection** | `FRIEND_REQUEST_REJECTED` | `/user/{username}/queue/messages` | Requester user | `id`, `recipientId`, `status: 'REJECTED'` |
| **Connection** | `FRIEND_REQUEST_CANCELLED`| `/user/{username}/queue/messages` | Recipient user | `id`, `requesterId`, `status: 'CANCELLED'` |
| **Connection** | `CONNECTION_REMOVED` | `/user/{username}/queue/messages` | Other user | `userId` (the user who was disconnected) |
| **Block** | `USER_BLOCKED` | `/user/{username}/queue/messages` | Blocked user | `blockedByUserId` |
| **Block** | `USER_UNBLOCKED` | `/user/{username}/queue/messages` | Unblocked user | `unblockedByUserId` |
| **Group Invite** | `GROUP_INVITATION_RECEIVED`| `/user/{username}/queue/messages`| Invitee user | `id`, `groupId`, `groupName`, `groupAvatarUrl`, `invitedByUserId`, `invitedByUsername`, `createdAt` |
| **Group Invite** | `GROUP_INVITATION_ACCEPTED`| `/user/{username}/queue/messages`| Inviter user | `id`, `groupId`, `inviteeUserId`, `status: 'ACCEPTED'` |
| **Group Invite** | `GROUP_INVITATION_REJECTED`| `/user/{username}/queue/messages`| Inviter user | `id`, `groupId`, `inviteeUserId`, `status: 'REJECTED'` |
| **Group Invite** | `GROUP_INVITATION_CANCELLED`| `/user/{username}/queue/messages`| Invitee user | `id`, `groupId`, `status: 'CANCELLED'` |
| **Group Member** | `GROUP_MEMBER_ADDED` | `/user/{username}/queue/messages` | Newly added user | `conversation` (`ConversationDto`), `group` (`GroupDto`), `addedByUserId` |
| **Group Member** | `GROUP_MEMBER_JOINED` | `/user/{username}/queue/messages` | Active group members | `conversationId`, `member` (`ConversationMemberDto`) |
| **Group Member** | `GROUP_MEMBER_REMOVED` | `/user/{username}/queue/messages` | Removed member | `conversationId`, `removedByUserId` |
| **Group Member** | `GROUP_MEMBER_LEFT` | `/user/{username}/queue/messages` | Remaining active members | `conversationId`, `userId` |
| **Group Member** | `GROUP_ROLE_CHANGED` | `/user/{username}/queue/messages` | All active members | `conversationId`, `userId`, `newRole` ('OWNER'/'ADMIN'/'MEMBER') |
| **Conversation** | `CONVERSATION_CREATED` | `/user/{username}/queue/messages` | Target user | `conversation` (`ConversationDto`) |

---

## 13. Minimal Safe Implementation Plan

The plan is divided into 7 distinct, non-breaking phases. Each phase can be implemented and verified independently.

### Phase 1: Reconnect & Full Reconciliation Foundation
- **Objective**: Fix the state machine bug in `App.tsx` and ensure that upon reconnection, the client reconciles all relationship, invitation, and group state.
- **Scope**:
  - In `App.tsx`: Fix `useEffect` for reconnect status to check `(status === 'CONNECTED' && prev !== 'CONNECTED' && prev !== '')`.
  - Add `loadRelationshipData()`, `loadGroupInvitations()`, and active conversation refetch to the reconnection routine.
- **Risk**: Very Low.

### Phase 2: Friend Request & Connection Real-Time Events
- **Objective**: Ensure that friend requests, acceptances, rejections, cancellations, and removals update both clients in real time.
- **Scope**:
  - In `ConnectionService.java`: Inject `SimpMessagingTemplate`, `AfterCommitExecutor`, and broadcast `FRIEND_REQUEST_RECEIVED`, `FRIEND_REQUEST_ACCEPTED`, `FRIEND_REQUEST_REJECTED`, `FRIEND_REQUEST_CANCELLED`, `CONNECTION_REMOVED`.
  - In `App.tsx`: Add handlers in `subscribe` to update `receivedRequestsByUserId`, `sentRequestsByUserId`, `connectedUserIds`.
- **Risk**: Low.

### Phase 3: User Block Real-Time Synchronization
- **Objective**: Ensure that blocking/unblocking a user immediately updates the peer's UI and locks messaging.
- **Scope**:
  - In `BlockService.java`: Broadcast `USER_BLOCKED` and `CONNECTION_REMOVED` to the target user upon block.
  - In `App.tsx`: Add handler to remove user from `connectedUserIds` and update `blockedUserIds`.
- **Risk**: Low.

### Phase 4: Group Invitation Real-Time Events
- **Objective**: Real-time synchronization of pending group invitations for both invitee and inviter.
- **Scope**:
  - In `GroupInvitationService.java`: Broadcast `GROUP_INVITATION_RECEIVED`, `GROUP_INVITATION_ACCEPTED`, `GROUP_INVITATION_REJECTED`, `GROUP_INVITATION_CANCELLED`.
  - In `App.tsx`: Add handlers to update `receivedGroupInvitations` and `sentGroupInvitations`.
- **Risk**: Low.

### Phase 5: Group Membership & Role Real-Time Events
- **Objective**: Real-time sidebar appearance on direct-add, member removal, voluntary leave, and role promotion/demotion.
- **Scope**:
  - In `GroupService.java` & `GroupInvitationService.java`: Broadcast `GROUP_MEMBER_ADDED`, `GROUP_MEMBER_JOINED`, `GROUP_MEMBER_REMOVED`, `GROUP_MEMBER_LEFT`, `GROUP_ROLE_CHANGED`.
  - In `App.tsx`: Add handlers to update `conversations`, `groupMembersById`, `groupInfoById`.
- **Risk**: Medium (Ensure E2EE key resolution and topic subscriptions are safely initialized/cleaned up).

### Phase 6: Direct Conversation Creation Event
- **Objective**: Ensure a newly created empty direct conversation appears in the target user's sidebar before the first message is sent.
- **Scope**:
  - In `ConversationService.java`: Broadcast `CONVERSATION_CREATED` on brand new direct conversation creation.
  - In `App.tsx`: Add handler to upsert conversation into `conversations`.
- **Risk**: Very Low.

### Phase 7: Race & Idempotency Hardening
- **Objective**: Guarantee that duplicate deliveries or out-of-order responses do not corrupt state.
- **Scope**:
  - Audit all `setX(prev => ...)` handlers in `App.tsx` to ensure map/set keys and IDs are used for strict deduplication.
- **Risk**: Low.

---

## 14. Files to Change (Implementation Scope)

### Backend Files
1. [`connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java)
   - Add `SimpMessagingTemplate`, `AfterCommitExecutor` dependencies.
   - Emit `FRIEND_REQUEST_RECEIVED`, `FRIEND_REQUEST_ACCEPTED`, `FRIEND_REQUEST_REJECTED`, `FRIEND_REQUEST_CANCELLED`, `CONNECTION_REMOVED`.
2. [`connectx-backend/src/main/java/com/connectx/block/service/BlockService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/block/service/BlockService.java)
   - Add `SimpMessagingTemplate`, `AfterCommitExecutor` dependencies.
   - Emit `USER_BLOCKED` and `CONNECTION_REMOVED`.
3. [`connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java)
   - Add `SimpMessagingTemplate`, `AfterCommitExecutor` dependencies.
   - Emit `GROUP_INVITATION_RECEIVED`, `GROUP_MEMBER_ADDED`, `GROUP_MEMBER_JOINED`, `GROUP_INVITATION_ACCEPTED`, `GROUP_INVITATION_REJECTED`, `GROUP_INVITATION_CANCELLED`.
4. [`connectx-backend/src/main/java/com/connectx/group/service/GroupService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java)
   - Emit `GROUP_MEMBER_REMOVED`, `GROUP_MEMBER_LEFT`, `GROUP_ROLE_CHANGED`.
5. [`connectx-backend/src/main/java/com/connectx/conversation/service/ConversationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/conversation/service/ConversationService.java)
   - Emit `CONVERSATION_CREATED` to target user in `createOrGetDirectConversation`.

### Frontend Files
1. [`connectx-frontend/src/App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx)
   - Fix reconnect state-machine effect (`prevWsStatusRef`).
   - Add comprehensive reconciliation calls on reconnect.
   - Add event branches in `subscribe(...)` for all missing events.
2. [`connectx-frontend/src/types/index.ts`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/types/index.ts)
   - Update `WsEvent` type union to include all new event names.

---

## 15. Files That Must NOT Be Touched ("Do Not Touch List")

To prevent any regressions to working cryptography, media, authentication, or messaging pipelines, the following files and directories must remain completely untouched:

| Subsystem | Protected Files / Directories | Rationale |
| :--- | :--- | :--- |
| **E2EE Direct Messaging** | [`connectx-frontend/src/crypto/encryption.ts`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/crypto/encryption.ts), [`decryption.ts`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/crypto/decryption.ts), [`keyManager.ts`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/crypto/keyManager.ts) | Working native WebCrypto ECDH P-256 / AES-GCM layer. |
| **Group Key Cryptography** | [`connectx-frontend/src/crypto/groupCrypto.ts`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/crypto/groupCrypto.ts), [`connectx-backend/src/main/java/com/connectx/group/service/GroupKeyService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupKeyService.java) | Working symmetric group key distribution and key wrapping. |
| **WebSocket Core & Security** | [`connectx-backend/src/main/java/com/connectx/config/WebSocketConfig.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/config/WebSocketConfig.java), [`WebSocketAuthChannelInterceptor.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/config/WebSocketAuthChannelInterceptor.java), [`SecurityConfig.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/config/SecurityConfig.java) | JWT authentication handshake and topic access control rules. |
| **Realtime Chat Pipeline** | `@MessageMapping("/message.send")` in [`WebSocketMessageController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/websocket/controller/WebSocketMessageController.java), [`MessageService.sendMessage`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/message/service/MessageService.java#L112) | Working live chat send, save, ACK, and fanout loop. |
| **Presence Engine** | [`connectx-backend/src/main/java/com/connectx/websocket/service/PresenceService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/websocket/service/PresenceService.java) | Working multi-session presence tracking with 8s offline debounce. |
| **Media Storage & Crypto** | [`connectx-backend/src/main/java/com/connectx/media/`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/media), [`connectx-backend/src/main/java/com/connectx/user/controller/ProfileImageController.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/user/controller/ProfileImageController.java) | Encrypted file uploads, disk storage, and stream endpoints. |
| **Database Schema** | JPA Entity definitions & column mappings | Database schema is stable; all events reuse existing DTO structures. |

---

## 16. Testing Strategy

### 16.1 Automated Testing
1. **Backend Integration Tests** (`@SpringBootTest`):
   - Verify that calling `connectionService.sendRequest()` publishes `FRIEND_REQUEST_RECEIVED` via `SimpMessagingTemplate`.
   - Verify that `connectionService.acceptRequest()` publishes `FRIEND_REQUEST_ACCEPTED`.
   - Verify that `groupInvitationService.createInvitation()` publishes `GROUP_INVITATION_RECEIVED` or `GROUP_MEMBER_ADDED`.
   - Verify that `groupService.removeMember()` publishes `GROUP_MEMBER_REMOVED`.
2. **Frontend Unit Tests** (Vitest):
   - Verify that receiving each `WsEvent` in `App.tsx` updates the corresponding state without mutation or duplicate entries.

### 16.2 Dual-Browser Multi-User End-to-End Verification
For every phase, test simultaneously with two active authenticated browser sessions (User A in Window 1, User B in Window 2 / Incognito):

1. **Friend Request Flow**:
   - User A clicks "Add Friend" on User B.
   - **Verification**: User B immediately sees the pending count badge and notification toast without clicking anything.
2. **Accept Request Flow**:
   - User B clicks "Accept".
   - **Verification**: User A's button immediately changes to "Message". User A clicks "Message" and sends an E2EE text without encountering `NOT_CONNECTED`.
3. **Group Direct-Add Flow**:
   - User A creates a group and direct-adds User B.
   - **Verification**: Group immediately appears in User B's sidebar. User B clicks it and can send messages.
4. **Group Removal Flow**:
   - User A removes User B from the group.
   - **Verification**: Group immediately disappears from User B's sidebar and the active chat screen closes.
5. **WebSocket Drop & Reconnect Flow**:
   - Simulate network disconnection (toggle offline in DevTools Network tab for User B).
   - User A sends a friend request and a message while User B is offline.
   - Toggle User B back online.
   - **Verification**: Upon reconnection, User B automatically reconciles and shows the friend request and new message.

---

## 17. Final Architecture Diagram

```
+-----------------------------------------------------------------------------------+
|                                  REACT CLIENT                                     |
|                                                                                   |
|  +---------------------------+                      +--------------------------+  |
|  |       REST CLIENT         |                      |     WEBSOCKET CLIENT     |  |
|  |      (apiClient.ts)       |                      |   (WebSocketClient.ts)   |  |
|  +-------------+-------------+                      +------------+-------------+  |
|                | (HTTP JWT)                                      | (STOMP /ws)    |
+----------------┼─────────────────────────────────────────────────┼----------------+
                 |                                                 |
                 ▼                                                 ▼
+-----------------------------------------------------------------------------------+
|                             SPRING BOOT BACKEND                                   |
|                                                                                   |
|  +---------------------------+                      +--------------------------+  |
|  |     REST CONTROLLERS      |                      |   WEBSOCKET CONTROLLER   |  |
|  | (Connection, Group, Auth) |                      | (@MessageMapping send)   |  |
|  +-------------+-------------+                      +------------+-------------+  |
|                |                                                 |                |
|                ▼                                                 ▼                |
|  +-----------------------------------------------------------------------------+  |
|  |                               SERVICES LAYER                                |  |
|  |       (ConnectionService, GroupService, MessageService, BlockService)       |  |
|  +-------------------------------------+---------------------------------------+  |
|                                        |                                          |
|                                        ▼                                          |
|  +-----------------------------------------------------------------------------+  |
|  |                       DATABASE TRANSACTION (@Transactional)                 |  |
|  |                MySQL Database / InnoDB Row Locks / Constraints              |  |
|  +-------------------------------------+---------------------------------------+  |
|                                        |                                          |
|                                        ▼ (AfterCommitExecutor)                    |
|  +-----------------------------------------------------------------------------+  |
|  |                           DOMAIN EVENT DISPATCHER                           |  |
|  |                            SimpMessagingTemplate                            |  |
|  +-------------------------------------+---------------------------------------+  |
|                                        |                                          |
+----------------------------------------┼------------------------------------------+
                                         |
                                         | Targeted WS Broadcast (/queue/messages)
                                         ▼
+-----------------------------------------------------------------------------------+
|                              REACT CLIENT (TARGET)                                |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  |                         WEBSOCKET EVENT DISPATCHER                          |  |
|  |                            (App.tsx: subscribe)                             |  |
|  +-------------------------------------+---------------------------------------+  |
|                                        |                                          |
|                                        ▼                                          |
|  +-----------------------------------------------------------------------------+  |
|  |                           CENTRAL REACT STATE                               |  |
|  | (connectedUserIds, receivedRequestsByUserId, receivedGroupInvitations, etc.)|  |
|  +-------------------------------------+---------------------------------------+  |
|                                        |                                          |
|                                        ▼                                          |
|  +-----------------------------------------------------------------------------+  |
|  |                                REACT UI                                     |  |
|  |        (Sidebar, Notification Badges, Modals, Chat Composer Gate)          |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

---

## 18. Risk Assessment

| Potential Risk | Severity | Mitigation Strategy |
| :--- | :--- | :--- |
| **Transaction Rollback Race** | High | Always wrap `messagingTemplate.convertAndSendToUser` inside `afterCommitExecutor.runAfterCommit(...)` so events only broadcast if the MySQL commit succeeds. |
| **Duplicate Event Delivery** | Medium | Use strict primary key identity deduplication in React state updaters (`Map.set`, `Set.add`, `prev.filter(id != targetId)`). |
| **Key Rotation Desynchronization** | High | Never modify existing `markKeyRotationRequired` logic; ensure `GROUP_MEMBER_ADDED` / `REMOVED` events trigger key cache invalidation cleanly. |
| **Stale Reconnect Storm** | Low | Reconnect handler reuses existing cached API endpoints (`loadRelationshipData`, `loadConversations`) which execute lightweight O(1) indexed SQL queries. |
| **STOMP Destination Permission Leak** | High | All personal events must be sent exclusively to `/user/{username}/queue/messages` (Spring user-destination mechanism), never to shared `/topic` channels. |

---

## 19. Recommended Implementation Order for Claude

To ensure flawless execution with zero regressions, the implementation should proceed in this strict order:

1. **Step 1: Reconnect & Reconciliation Foundation (Frontend)**
   - Fix `prevWsStatusRef` logic in [`App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx).
   - Add full reconciliation in reconnect effect.
2. **Step 2: Backend Event Publishing for Connections**
   - Inject `SimpMessagingTemplate` and `AfterCommitExecutor` into [`ConnectionService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/connection/service/ConnectionService.java).
   - Implement event emissions on `sendRequest`, `acceptRequest`, `rejectRequest`, `cancelRequest`, `removeConnection`.
3. **Step 3: Frontend Event Handlers for Connections**
   - Add TypeScript types to [`types/index.ts`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/types/index.ts).
   - Add event handlers in [`App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx).
4. **Step 4: Backend & Frontend User Block Events**
   - Update [`BlockService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/block/service/BlockService.java) and [`App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx).
5. **Step 5: Backend & Frontend Group Invitation Events**
   - Update [`GroupInvitationService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupInvitationService.java) and [`App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx).
6. **Step 6: Backend & Frontend Group Membership & Role Events**
   - Update [`GroupService.java`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-backend/src/main/java/com/connectx/group/service/GroupService.java) and [`App.tsx`](file:///d:/Vamsi/ConnectX-The%20Secure%20Messaging%20Service/connectx-frontend/src/App.tsx).
7. **Step 7: Verification & Multi-User Integration Testing**
   - Execute dual-browser tests across all 6 core workflows.
