package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.group.dto.GroupKeyRequestResultDto;
import com.connectx.group.dto.GroupMemberKeyDto;
import com.connectx.group.dto.SubmitGroupMemberKeyRequestDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupMemberKey;
import com.connectx.group.repository.GroupMemberKeyRepository;
import com.connectx.websocket.dto.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Groups Stage 6C: distribution of already-wrapped group-key material between members. This
 * service NEVER generates, receives, decrypts, unwraps, derives, stores, logs, or returns a
 * plaintext GROUP_KEY -- {@code wrappedKey}/{@code wrapNonce} are opaque strings from the moment
 * they arrive in {@link SubmitGroupMemberKeyRequestDto} to the moment they leave in
 * {@link GroupMemberKeyDto}; nothing here ever inspects their contents. Key generation, ECDH
 * wrapping, and unwrapping all happen client-side in a later stage.
 * <p>
 * Every authorization question -- group existence/type, actor active membership, target active
 * membership -- goes through {@link GroupAuthorizationService}, exactly like every other Groups
 * service; this service adds exactly one authorization rule of its own (key-version equality,
 * §Part 4) that has no home anywhere else, since {@code ChatGroup.keyVersion} is this stage's own
 * addition.
 */
@Service
public class GroupKeyService {

    private static final Logger log = LoggerFactory.getLogger(GroupKeyService.class);

    // Phase 7B: minimum gap between two rewrap-request broadcasts for the same (groupId,
    // requesterUserId), so a client that calls this repeatedly (e.g. its own bounded retry loop)
    // doesn't re-spam every other active member's queue on each attempt. In-memory only --
    // deliberately not persisted (see requestRewrap's own doc for why that's an acceptable
    // trade-off here), so it resets on restart; that just means a small burst of extra broadcasts
    // right after a deploy, never a correctness issue.
    private static final long REWRAP_REQUEST_THROTTLE_MS = 10_000;

    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupMemberKeyRepository groupMemberKeyRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    // Phase 7C: same package (com.connectx.group.service), so this reuses GroupService's
    // package-visible markKeyRotationRequired exactly like GroupInvitationService already does for
    // join events -- see recoverByRotating below for why this is now needed here too.
    private final GroupService groupService;
    private final Map<String, Instant> lastRewrapRequestAt = new ConcurrentHashMap<>();
    // Self-injected proxy so insertInNewTransaction's REQUIRES_NEW actually runs through Spring's
    // transactional AOP proxy when invoked from submitWrappedKey below -- same pattern and
    // rationale as GroupInvitationService#self: a duplicate (conversation_id, member_user_id)
    // insert must run in a genuinely separate transaction for the caller to recover from the lost
    // race by falling back to an UPDATE, since catching DataIntegrityViolationException from a
    // plain insert in the SAME transaction would still leave that transaction rollback-only.
    private final GroupKeyService self;

    public GroupKeyService(GroupAuthorizationService groupAuthorizationService,
                            GroupMemberKeyRepository groupMemberKeyRepository,
                            ConversationMemberRepository conversationMemberRepository,
                            SimpMessagingTemplate messagingTemplate,
                            @Lazy GroupService groupService,
                            @Lazy GroupKeyService self) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupMemberKeyRepository = groupMemberKeyRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.messagingTemplate = messagingTemplate;
        this.groupService = groupService;
        this.self = self;
    }

    /**
     * An active member submits an opaque wrapped copy of the group's CURRENT key for another
     * active member (or themselves). Rejects a target who is not currently an active member of
     * THIS exact group -- covers a never-member, a removed/left member, and a member of a
     * different group uniformly (all resolve to the same {@link GroupAuthorizationService.MembershipState}
     * other than ACTIVE_MEMBER), so a stale or forged target can never receive a new key row.
     * Rejects any keyVersion other than the group's current, server-authoritative
     * {@code ChatGroup.keyVersion} -- never rewritten to match the client, never auto-incremented;
     * no rotation happens in this stage.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GroupMemberKeyDto submitWrappedKey(Long actorUserId, Long groupId, SubmitGroupMemberKeyRequestDto dto) {
        ChatGroup chatGroup = groupAuthorizationService.requireActiveMember(actorUserId, groupId);

        Long targetUserId = dto.getMemberUserId();
        if (groupAuthorizationService.resolveMembershipState(groupId, targetUserId)
                != GroupAuthorizationService.MembershipState.ACTIVE_MEMBER) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER", "Target user is not an active member of this group");
        }

        int currentVersion = chatGroup.getKeyVersion();
        if (dto.getKeyVersion() != currentVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_VERSION_MISMATCH",
                    "Submitted key version does not match the group's current key version (" + currentVersion + ")");
        }

        GroupMemberKey saved = upsert(groupId, targetUserId, dto.getWrappedKey(), dto.getWrapNonce(), currentVersion, actorUserId);

        // Never log wrappedKey/wrapNonce contents -- only identifiers and the (non-secret) version.
        log.info("Group member key submitted: groupId={}, actorUserId={}, targetUserId={}, keyVersion={}",
                groupId, actorUserId, targetUserId, currentVersion);
        return toDto(saved);
    }

    private GroupMemberKey upsert(Long groupId, Long targetUserId, String wrappedKey, String wrapNonce, int keyVersion, Long wrappedByUserId) {
        return groupMemberKeyRepository.findByConversationIdAndMemberUserId(groupId, targetUserId)
                .map(existing -> {
                    existing.setWrappedKey(wrappedKey);
                    existing.setWrapNonce(wrapNonce);
                    existing.setKeyVersion(keyVersion);
                    existing.setWrappedByUserId(wrappedByUserId);
                    return groupMemberKeyRepository.save(existing);
                })
                .orElseGet(() -> insertOrFallBackToUpdate(groupId, targetUserId, wrappedKey, wrapNonce, keyVersion, wrappedByUserId));
    }

    private GroupMemberKey insertOrFallBackToUpdate(Long groupId, Long targetUserId, String wrappedKey, String wrapNonce, int keyVersion, Long wrappedByUserId) {
        try {
            return self.insertInNewTransaction(groupId, targetUserId, wrappedKey, wrapNonce, keyVersion, wrappedByUserId);
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race against a concurrent first-upload for the same
            // (conversation_id, member_user_id) pair -- the unique constraint from
            // V5__group_e2ee_key_model.sql caught it. The winner's row now exists; fall back to
            // updating it in place rather than surfacing an error, since this endpoint's contract
            // is "the current wrapped key for this member ends up as submitted", not "first
            // submission wins" -- exactly one row either way, never a duplicate.
            log.debug("Group member key insert lost race, updating existing row instead: groupId={}, targetUserId={}", groupId, targetUserId);
            GroupMemberKey existing = groupMemberKeyRepository.findByConversationIdAndMemberUserId(groupId, targetUserId)
                    .orElseThrow(() -> e);
            existing.setWrappedKey(wrappedKey);
            existing.setWrapNonce(wrapNonce);
            existing.setKeyVersion(keyVersion);
            existing.setWrappedByUserId(wrappedByUserId);
            return groupMemberKeyRepository.save(existing);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GroupMemberKey insertInNewTransaction(Long groupId, Long targetUserId, String wrappedKey, String wrapNonce, int keyVersion, Long wrappedByUserId) {
        return groupMemberKeyRepository.saveAndFlush(new GroupMemberKey(groupId, targetUserId, wrappedKey, wrapNonce, keyVersion, wrappedByUserId));
    }

    /**
     * The authenticated caller's own current wrapped key -- never another user's, never a query
     * parameter's, never every row for the group. IDOR boundary: {@code currentUserId} is the only
     * identity this method will ever look up a key for, and it comes exclusively from the security
     * principal at the controller (never a path/query/body value). A non-member gets the same
     * generic authorization failure {@link GroupAuthorizationService#requireActiveMember} throws
     * for every other group endpoint -- no distinct message reveals whether the group exists, was
     * previously joined, or has any key rows at all.
     */
    @Transactional(readOnly = true)
    public GroupMemberKeyDto getMyWrappedKey(Long currentUserId, Long groupId) {
        groupAuthorizationService.requireActiveMember(currentUserId, groupId);

        GroupMemberKey key = groupMemberKeyRepository.findByConversationIdAndMemberUserId(groupId, currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_KEY_NOT_FOUND", "No wrapped group key exists for you yet"));
        return toDto(key);
    }

    /**
     * Phase 7B key reconciliation: an active member who has determined (client-side, in
     * groupKeyManager) that they hold no usable copy of the group's CURRENT key asks every OTHER
     * active member's client to re-wrap that key for them. This is deliberately NOT a rotation --
     * {@code ChatGroup.keyVersion} is never touched here, unlike {@link #markKeyRotationRequired}
     * (package-visible in GroupService) which the caller must never be tempted to call just
     * because its own copy is missing (see groupKeyManager.ts's own doc for the failure mode this
     * replaces: a device with a gap self-electing as rotator and minting a brand-new key/version
     * purely to route around its own missing row).
     * <p>
     * This method never reads, returns, or even knows whether any wrapped key material exists for
     * anyone -- it only (a) confirms the requester is a genuine active member of THIS group (so a
     * removed/left/never-member can't make every other member's client do ECDH work on their
     * behalf, and can never learn who else is in the group from this call), and (b) fans out a
     * content-free WS notice carrying only the group id, the server-authoritative key version, and
     * the requester's id -- to every OTHER active member's personal queue, exactly the delivery
     * mechanism {@link #markKeyRotationRequired} already uses. Whichever recipient(s) actually
     * hold that exact key version decide, entirely client-side, whether to act on it (re-wrap their
     * own already-resolved plaintext key for the requester's public key and POST it back via the
     * existing, unmodified submitWrappedKey endpoint above -- which already allows any active
     * member to submit a wrapped copy targeting any other active member). A member who does not
     * hold that version simply ignores the notice. No new persistence: nothing here needs to
     * survive a restart, so an in-memory per-(group,requester) throttle is sufficient. Idempotent
     * by construction -- calling this again for the same group/requester either re-broadcasts (if
     * outside the throttle window) or no-ops (if within it), and the eventual re-submission it may
     * trigger is itself an upsert (see submitWrappedKey's own doc), so repeated requests can never
     * create duplicate rows or divergent state.
     */
    @Transactional(readOnly = true)
    public GroupKeyRequestResultDto requestRewrap(Long requesterUserId, Long groupId) {
        ChatGroup chatGroup = groupAuthorizationService.requireActiveMember(requesterUserId, groupId);
        int currentVersion = chatGroup.getKeyVersion();

        String throttleKey = groupId + ":" + requesterUserId;
        Instant now = Instant.now();
        Instant last = lastRewrapRequestAt.get(throttleKey);
        if (last != null && now.toEpochMilli() - last.toEpochMilli() < REWRAP_REQUEST_THROTTLE_MS) {
            log.debug("Group key rewrap request throttled: groupId={}, requesterUserId={}", groupId, requesterUserId);
            return new GroupKeyRequestResultDto(currentVersion, false);
        }
        lastRewrapRequestAt.put(throttleKey, now);

        // Phase 7C fix (found via live multi-device testing): this must NOT exclude the requester's
        // own USERNAME -- ConversationMember/username identify the ACCOUNT, not the session/device,
        // so excluding "the requester's username" excluded every one of that account's OTHER
        // sessions/devices too. That is exactly backwards: the primary scenario this feature exists
        // for is the SAME account's second device/browser (different session, identical userId)
        // holding the current key while the requesting session doesn't. Broadcasting to every
        // active member's username -- including the requester's own -- lets Spring's per-username
        // STOMP fan-out (convertAndSendToUser) reach that other session; the requester's OWN
        // originating session also receives a copy, which is harmless (groupKeyManager's
        // fulfillRewrapRequest resolves passively and simply no-ops if it doesn't hold the key
        // either, or harmlessly re-submits its own already-correct row if it does).
        List<ConversationMember> activeMembers = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<String> usernames = activeMembers.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> m.getUser().getUsername())
                .collect(Collectors.toList());

        WsEvent rewrapRequestedEvent = WsEvent.of("GROUP_KEY_REWRAP_REQUESTED",
                Map.of("conversationId", groupId, "keyVersion", currentVersion, "requestingUserId", requesterUserId));
        for (String username : usernames) {
            messagingTemplate.convertAndSendToUser(username, "/queue/messages", rewrapRequestedEvent);
        }

        log.info("Group key rewrap requested: groupId={}, requesterUserId={}, keyVersion={}, notifiedCount={}",
                groupId, requesterUserId, currentVersion, usernames.size());
        return new GroupKeyRequestResultDto(currentVersion, true);
    }

    /**
     * Phase 7C: the mint-fallback's safe replacement for silently reusing the CURRENT authoritative
     * version. Live multi-device testing proved that distributing freshly minted key material under
     * {@code ChatGroup.keyVersion} AS-IS (when no reconciliation fulfillment arrived in time) is
     * unsafe: other members may already hold real, different key material for that exact version
     * number (e.g. from the legitimate rotation that created it), and overwriting their
     * {@code group_member_keys} rows with a different key under the SAME version number corrupts
     * decryption for every member who already cached the old value locally -- not merely "wastes" a
     * version. This method instead claims a genuinely NEW version via the same
     * {@link GroupService#markKeyRotationRequired} every membership-triggered rotation already
     * uses, so the client-minted key is guaranteed to be the first (and only) material anyone
     * distributes under that new number. Requires active membership, exactly like every other
     * group-key operation; does not itself touch key material.
     */
    @Transactional
    public int recoverByRotating(Long actorUserId, Long groupId) {
        groupAuthorizationService.requireActiveMember(actorUserId, groupId);
        int newVersion = groupService.markKeyRotationRequired(groupId);
        log.info("Group key recovery rotation: groupId={}, actorUserId={}, newKeyVersion={}", groupId, actorUserId, newVersion);
        return newVersion;
    }

    private GroupMemberKeyDto toDto(GroupMemberKey key) {
        return new GroupMemberKeyDto(key.getConversationId(), key.getKeyVersion(), key.getWrappedKey(), key.getWrapNonce(), key.getWrappedByUserId());
    }
}
