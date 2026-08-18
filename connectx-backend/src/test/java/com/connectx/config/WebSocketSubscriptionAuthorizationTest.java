package com.connectx.config;

import com.connectx.common.security.UserPrincipal;
import com.connectx.conversation.entity.Conversation;
import com.connectx.conversation.entity.ConversationMember;
import com.connectx.conversation.entity.ConversationType;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.conversation.repository.ConversationRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groups Stage 0: WebSocketAuthChannelInterceptor previously only authenticated CONNECT frames and
 * never authorized SUBSCRIBE frames, so any authenticated client could subscribe to
 * /topic/conversation/{anyConversationId} regardless of membership. Follows the real-MySQL,
 * service/repository-layer conventions used by DirectConversationAuthorizationTest and
 * BlockRaceIntegrationTest -- the interceptor bean itself is exercised directly against real
 * StompHeaderAccessor frames rather than mocked, since it has no meaningful behavior worth mocking
 * around.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebSocketSubscriptionAuthorizationTest {

    @Autowired
    private WebSocketAuthChannelInterceptor interceptor;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private ConversationMemberRepository conversationMemberRepository;
    @Autowired
    private UserRepository userRepository;

    private User newUser(String label) {
        long n = System.nanoTime();
        return userRepository.save(new User(label + "_" + n, label + "_" + n + "@test.com", "hash", label));
    }

    private Conversation directConversationBetween(User a, User b) {
        Conversation direct = conversationRepository.save(new Conversation(ConversationType.DIRECT));
        conversationMemberRepository.save(new ConversationMember(direct, a));
        conversationMemberRepository.save(new ConversationMember(direct, b));
        return direct;
    }

    private Message<byte[]> subscribeFrame(String destination, User principalUser) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (principalUser != null) {
            UserPrincipal userPrincipal = UserPrincipal.create(principalUser);
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    userPrincipal, null, userPrincipal.getAuthorities());
            accessor.setUser(auth);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // 1. authenticated user subscribes to own DIRECT conversation -> allowed
    @Test
    void ownDirectConversation_subscriptionAllowed() {
        User a = newUser("own_a");
        User b = newUser("own_b");
        Conversation direct = directConversationBetween(a, b);

        assertDoesNotThrow(() ->
                interceptor.preSend(subscribeFrame("/topic/conversation/" + direct.getId(), a), null));
    }

    // 2. authenticated user subscribes to another user's DIRECT conversation -> rejected
    @Test
    void foreignDirectConversation_subscriptionRejected() {
        User a = newUser("foreign_a");
        User b = newUser("foreign_b");
        User outsider = newUser("foreign_outsider");
        Conversation direct = directConversationBetween(a, b);

        assertThrows(MessagingException.class, () ->
                interceptor.preSend(subscribeFrame("/topic/conversation/" + direct.getId(), outsider), null));
    }

    // 3. authenticated user subscribes to a conversation where they were soft-deleted -> rejected
    @Test
    void softDeletedMembership_subscriptionRejected() {
        User a = newUser("softdel_a");
        User b = newUser("softdel_b");
        Conversation direct = directConversationBetween(a, b);

        ConversationMember membership = conversationMemberRepository.findByConversationIdAndUserId(direct.getId(), a.getId())
                .orElseThrow();
        membership.setDeletedAt(Instant.now());
        conversationMemberRepository.save(membership);

        assertThrows(MessagingException.class, () ->
                interceptor.preSend(subscribeFrame("/topic/conversation/" + direct.getId(), a), null));
    }

    // 4. unauthenticated SUBSCRIBE -> rejected
    @Test
    void unauthenticatedSubscribe_rejected() {
        User a = newUser("unauth_a");
        User b = newUser("unauth_b");
        Conversation direct = directConversationBetween(a, b);

        assertThrows(MessagingException.class, () ->
                interceptor.preSend(subscribeFrame("/topic/conversation/" + direct.getId(), null), null));
    }

    // 5. malformed conversation destination -> rejected safely
    @Test
    void malformedConversationId_rejectedSafely() {
        User a = newUser("malformed_a");

        assertThrows(MessagingException.class, () ->
                interceptor.preSend(subscribeFrame("/topic/conversation/not-a-number", a), null));
    }

    // 6. non-existent conversation id -> rejected safely
    @Test
    void nonExistentConversationId_rejectedSafely() {
        User a = newUser("nonexistent_a");

        assertThrows(MessagingException.class, () ->
                interceptor.preSend(subscribeFrame("/topic/conversation/999999999", a), null));
    }

    // 7. after membership is restored -> subscription allowed
    @Test
    void restoredMembership_subscriptionAllowed() {
        User a = newUser("restored_a");
        User b = newUser("restored_b");
        Conversation direct = directConversationBetween(a, b);

        ConversationMember membership = conversationMemberRepository.findByConversationIdAndUserId(direct.getId(), a.getId())
                .orElseThrow();
        membership.setDeletedAt(Instant.now());
        conversationMemberRepository.save(membership);
        assertThrows(MessagingException.class, () ->
                interceptor.preSend(subscribeFrame("/topic/conversation/" + direct.getId(), a), null));

        membership.setDeletedAt(null);
        conversationMemberRepository.save(membership);
        assertDoesNotThrow(() ->
                interceptor.preSend(subscribeFrame("/topic/conversation/" + direct.getId(), a), null));
    }

    // Other STOMP destinations (e.g. /user/queue/*) must be unaffected by this check.
    @Test
    void nonConversationTopicDestination_untouched() {
        User a = newUser("other_dest_a");

        assertDoesNotThrow(() ->
                interceptor.preSend(subscribeFrame("/user/queue/messages", a), null));
    }
}
