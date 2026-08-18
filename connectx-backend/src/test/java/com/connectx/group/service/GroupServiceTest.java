package com.connectx.group.service;

import com.connectx.common.exception.ApiException;
import com.connectx.conversation.dto.ConversationDto;
import com.connectx.conversation.dto.ConversationMemberDto;
import com.connectx.conversation.dto.CreateDirectConversationDto;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.entity.GroupRole;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.conversation.service.ConversationService;
import com.connectx.connection.service.ConnectionService;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.group.dto.CreateGroupRequestDto;
import com.connectx.group.dto.GroupDto;
import com.connectx.group.entity.ChatGroup;
import com.connectx.group.repository.ChatGroupRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 1: backend foundation for group creation/retrieval. Follows the same real-MySQL,
 * service-layer conventions as DirectConversationAuthorizationTest -- GroupService is exercised
 * directly rather than through MockMvc, since forging a creator/role has nothing to do with the
 * HTTP layer here (CreateGroupRequestDto structurally carries neither field).
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupServiceTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConnectionService connectionService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private ChatGroupRepository chatGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    // 1, 2, 3, 4, 5. authenticated user can create a group; creator becomes OWNER; the
    // Conversation is type GROUP; ChatGroup is linked to that same conversation id; creator is an
    // active member.
    @Test
    void createGroup_producesLinkedConversationChatGroupAndOwnerMembership() {
        User creator = newUser("create_a");

        GroupDto group = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("Study Group", "Weekly sync"));

        assertNotNull(group.getId());
        assertEquals("GROUP", group.getType());
        assertEquals("Study Group", group.getName());
        assertEquals("Weekly sync", group.getDescription());
        assertEquals(creator.getId(), group.getCreatedByUserId());
        assertEquals("OWNER", group.getCurrentUserRole());
        assertEquals(1L, group.getActiveMemberCount());

        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();
        assertEquals(ConversationType.GROUP, conversation.getType());

        ChatGroup chatGroup = chatGroupRepository.findById(group.getId()).orElseThrow();
        assertEquals(group.getId(), chatGroup.getConversationId());
        assertEquals(group.getId(), chatGroup.getConversation().getId());

        ConversationMember ownerMembership = conversationMemberRepository
                .findByConversationIdAndUserId(group.getId(), creator.getId())
                .orElseThrow();
        assertNull(ownerMembership.getDeletedAt());
        assertEquals(GroupRole.OWNER, ownerMembership.getRole());
    }

    // 6, 7. a non-member cannot retrieve group details or the member list.
    @Test
    void nonMember_cannotRetrieveGroupDetailsOrMembers() {
        User creator = newUser("priv_owner");
        User outsider = newUser("priv_outsider");
        GroupDto group = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("Private Group", null));

        ApiException detailsEx = assertThrows(ApiException.class,
                () -> groupService.getGroupDetails(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", detailsEx.getCode());

        ApiException membersEx = assertThrows(ApiException.class,
                () -> groupService.getGroupMembers(outsider.getId(), group.getId()));
        assertEquals("NOT_GROUP_MEMBER", membersEx.getCode());
    }

    // 8, 9. an active member can retrieve group details, and the member list contains exactly the
    // correct users (never leaking email -- PublicUserDto has no email field to leak).
    @Test
    void activeMember_canRetrieveGroupDetailsAndCorrectMemberList() {
        User creator = newUser("list_owner");
        GroupDto group = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("Roster Group", null));

        GroupDto fetched = groupService.getGroupDetails(creator.getId(), group.getId());
        assertEquals(group.getId(), fetched.getId());
        assertEquals("Roster Group", fetched.getName());

        List<ConversationMemberDto> members = groupService.getGroupMembers(creator.getId(), group.getId());
        assertEquals(1, members.size());
        assertEquals(creator.getId(), members.get(0).getUser().getId());
        assertEquals("OWNER", members.get(0).getRole());
    }

    // 10, 11. the 100-active-member cap is enforced, and soft-deleted members don't count toward
    // it.
    @Test
    void memberLimit_enforcedButExcludesSoftDeletedMembers() {
        User creator = newUser("limit_owner");
        GroupDto group = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("Big Group", null));
        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();

        // Creator already occupies slot 1; fill 98 more active slots directly (99 total), then
        // soft-delete one of them so the active count is 98 again.
        User softDeletedUser = null;
        for (int i = 0; i < 98; i++) {
            User filler = newUser("limit_filler_" + i);
            ConversationMember member = new ConversationMember(conversation, filler);
            member.setRole(GroupRole.MEMBER);
            conversationMemberRepository.save(member);
            if (i == 0) {
                softDeletedUser = filler;
            }
        }
        assertEquals(99, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));

        // One more active member reaches exactly 100 -- still allowed.
        User the100th = newUser("limit_100th");
        groupService.addMember(conversation, the100th, GroupRole.MEMBER, creator.getId());
        assertEquals(100, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));

        // The 101st active member must be rejected.
        User the101st = newUser("limit_101st");
        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.addMember(conversation, the101st, GroupRole.MEMBER, creator.getId()));
        assertEquals("GROUP_MEMBER_LIMIT_EXCEEDED", ex.getCode());

        // Soft-delete one existing member -- the active count drops below the cap, so a new member
        // can now be added even though 100 ConversationMember rows still exist for this group.
        ConversationMember toRemove = conversationMemberRepository
                .findByConversationIdAndUserId(group.getId(), softDeletedUser.getId())
                .orElseThrow();
        toRemove.setDeletedAt(Instant.now());
        conversationMemberRepository.save(toRemove);
        assertEquals(99, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));

        User the101stRetry = newUser("limit_101st_retry");
        assertDoesNotThrow(() -> groupService.addMember(conversation, the101stRetry, GroupRole.MEMBER, creator.getId()));
        assertEquals(100, conversationMemberRepository.countByConversationIdAndDeletedAtIsNull(group.getId()));
    }

    // 12. adding the same active member twice is rejected -- no duplicate row is ever created.
    @Test
    void duplicateActiveMembership_prevented() {
        User creator = newUser("dup_owner");
        GroupDto group = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("Dup Group", null));
        Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();

        ApiException ex = assertThrows(ApiException.class,
                () -> groupService.addMember(conversation, creator, GroupRole.MEMBER, null));
        assertEquals("ALREADY_GROUP_MEMBER", ex.getCode());

        long rowsForCreator = conversationMemberRepository.findByConversationId(group.getId()).stream()
                .filter(m -> m.getUser().getId().equals(creator.getId()))
                .count();
        assertEquals(1, rowsForCreator, "exactly one ConversationMember row must exist for the creator");
    }

    // 13, 14. CreateGroupRequestDto has no creatorUserId or role field to forge -- ownership and
    // role are derived purely from the authenticated caller passed into createGroup, never from
    // request content. Demonstrated by creating groups as two different callers with the exact
    // same DTO shape and confirming each group's owner matches its own caller, never the other.
    @Test
    void creatorAndRole_cannotBeForgedThroughRequestDto() {
        User callerA = newUser("forge_caller_a");
        User callerB = newUser("forge_caller_b");
        CreateGroupRequestDto sameShapeDto = new CreateGroupRequestDto("Forge Test Group", null);

        GroupDto groupA = groupService.createGroup(callerA.getId(), sameShapeDto);
        GroupDto groupB = groupService.createGroup(callerB.getId(), sameShapeDto);

        assertEquals(callerA.getId(), groupA.getCreatedByUserId());
        assertEquals(callerB.getId(), groupB.getCreatedByUserId());

        GroupRole roleAInGroupA = conversationMemberRepository.findByConversationIdAndUserId(groupA.getId(), callerA.getId())
                .orElseThrow().getRole();
        GroupRole roleBInGroupB = conversationMemberRepository.findByConversationIdAndUserId(groupB.getId(), callerB.getId())
                .orElseThrow().getRole();
        assertEquals(GroupRole.OWNER, roleAInGroupA);
        assertEquals(GroupRole.OWNER, roleBInGroupB);

        // Neither caller ends up with any membership in the other's group.
        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(groupA.getId(), callerB.getId()).isEmpty());
        assertTrue(conversationMemberRepository.findByConversationIdAndUserId(groupB.getId(), callerA.getId()).isEmpty());
    }

    // 15. DIRECT conversation creation is untouched by the group foundation.
    @Test
    void directConversationCreation_stillWorks() {
        User a = newUser("direct_regress_a");
        User b = newUser("direct_regress_b");
        connectionService.sendRequest(a.getId(), new SendConnectionRequestDto(b.getId()));
        connectionService.acceptRequest(b.getId(),
                connectionService.getPendingIncomingRequests(b.getId()).get(0).getId());

        ConversationDto direct = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));

        assertEquals("DIRECT", direct.getType());
        ConversationMember member = conversationMemberRepository.findByConversationIdAndUserId(direct.getId(), a.getId()).orElseThrow();
        assertNull(member.getRole(), "DIRECT members must never get a group role assigned");
    }

    // 16. a pre-existing (legacy-style) DIRECT conversation, created the same way older rows were
    // (no connection required), remains fully unaffected -- its members keep a NULL role and stay
    // retrievable exactly as before.
    @Test
    void existingDirectConversations_remainUnaffected() {
        User a = newUser("direct_legacy_a");
        User b = newUser("direct_legacy_b");

        Conversation legacyDirect = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(legacyDirect, a));
        conversationMemberRepository.save(new ConversationMember(legacyDirect, b));

        ConversationDto reopened = conversationService.createOrGetDirectConversation(a.getId(), new CreateDirectConversationDto(b.getId()));
        assertEquals(legacyDirect.getId(), reopened.getId());
        assertEquals("DIRECT", reopened.getType());

        ConversationMember memberA = conversationMemberRepository.findByConversationIdAndUserId(legacyDirect.getId(), a.getId()).orElseThrow();
        ConversationMember memberB = conversationMemberRepository.findByConversationIdAndUserId(legacyDirect.getId(), b.getId()).orElseThrow();
        assertNull(memberA.getRole());
        assertNull(memberB.getRole());
        assertNull(memberA.getInvitedByUserId());
    }

    // Transaction rollback: if any step in an atomic group-membership write fails, nothing from
    // that transaction is persisted -- not the Conversation, not the ChatGroup, not the
    // ConversationMember rows already written earlier in the same transaction. createGroup itself
    // has no legitimate way to fail after its writes begin (a brand-new conversation can never
    // already be at the member cap or already contain the creator), so the failure is injected via
    // a second, deliberately duplicate addMember call composed into the same outer transaction --
    // this exercises the exact rollback mechanism (@Transactional + RuntimeException) that
    // createGroup's own atomicity guarantee depends on.
    @Test
    void groupCreationTransaction_rollsBackCompletelyOnMidwayFailure() {
        User creator = newUser("rollback_owner");
        long conversationCountBefore = conversationRepository.count();
        long chatGroupCountBefore = chatGroupRepository.count();
        long memberCountBefore = conversationMemberRepository.count();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThrows(ApiException.class, () -> txTemplate.execute(status -> {
            GroupDto group = groupService.createGroup(creator.getId(), new CreateGroupRequestDto("Rollback Group", null));
            Conversation conversation = conversationRepository.findById(group.getId()).orElseThrow();
            // Duplicate add -- fails with ALREADY_GROUP_MEMBER, forcing the whole outer transaction
            // (including the createGroup writes above) to roll back.
            groupService.addMember(conversation, creator, GroupRole.MEMBER, null);
            return null;
        }));

        assertEquals(conversationCountBefore, conversationRepository.count());
        assertEquals(chatGroupCountBefore, chatGroupRepository.count());
        assertEquals(memberCountBefore, conversationMemberRepository.count());
    }
}
