package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.service.ProfileVisibilityService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Groups Stage 1 backend foundation. A GROUP is a Conversation (type=GROUP) + a ChatGroup row +
 * ConversationMember rows with a role -- deliberately not a parallel hierarchy, so every other
 * conversation-scoped mechanism (messaging, WebSocket topic authorization from Stage 0, pin/mute/
 * archive) keeps working against a group's conversationId without modification.
 * <p>
 * ConversationService remains responsible for generic, DIRECT-or-GROUP-agnostic conversation
 * operations; this service owns everything group-specific (creation, role assignment, membership
 * limits) so ConversationService doesn't need to grow group-shaped branches.
 */
@Service
public class GroupService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final UserRepository userRepository;
    private final ProfileVisibilityService profileVisibilityService;
    private final GroupAuthorizationService groupAuthorizationService;

    public GroupService(ConversationRepository conversationRepository,
                         ConversationMemberRepository conversationMemberRepository,
                         ChatGroupRepository chatGroupRepository,
                         UserRepository userRepository,
                         ProfileVisibilityService profileVisibilityService,
                         GroupAuthorizationService groupAuthorizationService) {
        this.conversationRepository = conversationRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.userRepository = userRepository;
        this.profileVisibilityService = profileVisibilityService;
        this.groupAuthorizationService = groupAuthorizationService;
    }

    @Transactional
    public GroupDto createGroup(Long currentUserId, CreateGroupRequestDto dto) {
        String name = validateName(dto.getName());
        String description = validateDescription(dto.getDescription());

        // The authenticated caller is always the creator/owner -- CreateGroupRequestDto has no
        // creatorUserId or role field, so there is nothing here for a client to forge either with.
        User creator = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Current user not found"));

        Conversation conversation = conversationRepository.save(new Conversation(ConversationType.GROUP));

        ChatGroup chatGroup = new ChatGroup(conversation, name, description, creator);
        chatGroup = chatGroupRepository.save(chatGroup);

        ConversationMember ownerMembership = addMember(conversation, creator, GroupRole.OWNER, null);
        conversation.getMembers().add(ownerMembership);

        return buildGroupDto(conversation, chatGroup, 1L, GroupRole.OWNER);
    }

    @Transactional(readOnly = true)
    public GroupDto getGroupDetails(Long currentUserId, Long groupId) {
        ChatGroup chatGroup = groupAuthorizationService.requireActiveMember(currentUserId, groupId);
        long activeMemberCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(groupId);
        GroupRole viewerRole = conversationMemberRepository.findByConversationIdAndUserId(groupId, currentUserId)
                .map(ConversationMember::getRole)
                .orElse(null);
        return buildGroupDto(chatGroup.getConversation(), chatGroup, activeMemberCount, viewerRole);
    }

    @Transactional(readOnly = true)
    public List<ConversationMemberDto> getGroupMembers(Long currentUserId, Long groupId) {
        groupAuthorizationService.requireActiveMember(currentUserId, groupId);

        List<ConversationMember> members = conversationMemberRepository
                .findByConversationIdAndDeletedAtIsNullWithUsers(groupId);
        List<User> memberUsers = members.stream().map(ConversationMember::getUser).collect(Collectors.toList());
        Map<Long, Boolean> photoVisibilityByUserId = profileVisibilityService.resolvePhotoVisibility(currentUserId, memberUsers);

        return members.stream()
                .map(m -> ConversationMemberDto.fromEntity(m, photoVisibilityByUserId.getOrDefault(m.getUser().getId(), false)))
                .collect(Collectors.toList());
    }

    /**
     * Adds a brand-new active membership row for {@code user} in {@code conversation}, under the
     * central {@link GroupAuthorizationService#MAX_ACTIVE_GROUP_MEMBERS} cap. Not yet wired to a
     * REST endpoint -- Stage 1 only ever calls this for the creator at group-creation time, when
     * the state is always NEVER_MEMBER -- but it is the shared primitive a later invite-accept
     * stage will call, so the limit/duplicate/reactivation enforcement lives here rather than
     * being re-derived per call site.
     * <p>
     * Stage 1 originally restored an existing soft-deleted row here (deletedAt -> null),
     * mirroring ConversationService's DIRECT-conversation restore-on-reopen pattern. Stage 1.5's
     * consent rule makes that wrong for GROUP membership: a soft-deleted row can mean the user
     * left or was removed, and either case must require an explicit fresh invitation/acceptance
     * (Stage 2, not yet implemented) rather than being silently reactivated by a plain add -- see
     * GroupAuthorizationService's class-level javadoc on the LEFT/REMOVED state collapse. This
     * method now rejects that case instead of restoring.
     * <p>
     * Deliberately does not use the REQUIRES_NEW self-proxy + DataIntegrityViolationException
     * pattern used elsewhere (e.g. ConnectionService#insertConnectionInNewTransaction): that
     * pattern exists to recover cleanly from a lost race against a concurrent duplicate insert,
     * which requires a DB-level uniqueness guard to race against. conversation_members has no such
     * constraint on (conversation_id, user_id) today, and Stage 1.5 still has no concurrent caller
     * of this method (group creation only ever adds the single creator). Add both the constraint
     * and the REQUIRES_NEW guard together when a later stage introduces concurrent member-adding
     * (e.g. invite-accept) -- flagged here explicitly so that stage doesn't skip it.
     */
    @Transactional
    public ConversationMember addMember(Conversation conversation, User user, GroupRole role, Long invitedByUserId) {
        Long conversationId = conversation.getId();

        GroupAuthorizationService.MembershipState state = groupAuthorizationService.resolveMembershipState(conversationId, user.getId());
        if (state == GroupAuthorizationService.MembershipState.ACTIVE_MEMBER) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_GROUP_MEMBER", "User is already a member of this group");
        }
        if (state == GroupAuthorizationService.MembershipState.INACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_REINVITATION_REQUIRED",
                    "This user previously left or was removed from the group and must be re-invited");
        }

        long activeCount = conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(conversationId);
        if (activeCount >= GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_MEMBER_LIMIT_EXCEEDED",
                    "This group has reached the maximum of " + GroupAuthorizationService.MAX_ACTIVE_GROUP_MEMBERS + " members");
        }

        ConversationMember member = new ConversationMember(conversation, user);
        member.setRole(role);
        member.setInvitedByUserId(invitedByUserId);
        return conversationMemberRepository.save(member);
    }

    private GroupDto buildGroupDto(Conversation conversation, ChatGroup chatGroup, long activeMemberCount, GroupRole viewerRole) {
        GroupDto dto = new GroupDto();
        dto.setId(conversation.getId());
        dto.setType(conversation.getType().name());
        dto.setName(chatGroup.getName());
        dto.setDescription(chatGroup.getDescription());
        dto.setAvatarUrl(chatGroup.getAvatarUrl());
        dto.setWhoCanInvite(chatGroup.getWhoCanInvite().name());
        dto.setCreatedByUserId(chatGroup.getCreatedByUser().getId());
        dto.setCurrentUserRole(viewerRole != null ? viewerRole.name() : null);
        dto.setActiveMemberCount(activeMemberCount);
        dto.setCreatedAt(chatGroup.getCreatedAt());
        dto.setUpdatedAt(chatGroup.getUpdatedAt());
        return dto;
    }

    private String validateName(String rawName) {
        String name = rawName != null ? rawName.trim() : "";
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_NAME_REQUIRED", "Group name is required");
        }
        if (name.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_NAME_TOO_LONG", "Group name must be 100 characters or fewer");
        }
        return name;
    }

    private String validateDescription(String rawDescription) {
        if (rawDescription == null) {
            return null;
        }
        String description = rawDescription.trim();
        if (description.isEmpty()) {
            return null;
        }
        if (description.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_DESCRIPTION_TOO_LONG", "Group description must be 500 characters or fewer");
        }
        return description;
    }
}
