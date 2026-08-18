package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.group.dto.GroupMemberKeyDto;
import com.connectx.group.dto.SubmitGroupMemberKeyRequestDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.entity.GroupMemberKey;
import com.connectx.group.repository.GroupMemberKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final GroupAuthorizationService groupAuthorizationService;
    private final GroupMemberKeyRepository groupMemberKeyRepository;
    // Self-injected proxy so insertInNewTransaction's REQUIRES_NEW actually runs through Spring's
    // transactional AOP proxy when invoked from submitWrappedKey below -- same pattern and
    // rationale as GroupInvitationService#self: a duplicate (conversation_id, member_user_id)
    // insert must run in a genuinely separate transaction for the caller to recover from the lost
    // race by falling back to an UPDATE, since catching DataIntegrityViolationException from a
    // plain insert in the SAME transaction would still leave that transaction rollback-only.
    private final GroupKeyService self;

    public GroupKeyService(GroupAuthorizationService groupAuthorizationService,
                            GroupMemberKeyRepository groupMemberKeyRepository,
                            @Lazy GroupKeyService self) {
        this.groupAuthorizationService = groupAuthorizationService;
        this.groupMemberKeyRepository = groupMemberKeyRepository;
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

    private GroupMemberKeyDto toDto(GroupMemberKey key) {
        return new GroupMemberKeyDto(key.getConversationId(), key.getKeyVersion(), key.getWrappedKey(), key.getWrapNonce(), key.getWrappedByUserId());
    }
}
