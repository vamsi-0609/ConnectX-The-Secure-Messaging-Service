package com.connectx.config;

import com.connectx.common.security.CustomUserDetailsService;
import com.connectx.common.security.JwtTokenProvider;
import com.connectx.common.security.UserPrincipal;
import com.connectx.conversation.repository.ConversationMemberRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    // Matches exactly "/topic/conversation/{conversationId}" -- the only STOMP topic that carries
    // per-conversation data. Other destinations (/user/queue/*) are already scoped to the
    // authenticated session by Spring's user-destination mechanism and need no extra check here.
    private static final Pattern CONVERSATION_TOPIC_PATTERN = Pattern.compile("^/topic/conversation/([^/]+)$");

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final ConversationMemberRepository conversationMemberRepository;

    public WebSocketAuthChannelInterceptor(JwtTokenProvider tokenProvider,
                                           CustomUserDetailsService userDetailsService,
                                           ConversationMemberRepository conversationMemberRepository) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
        this.conversationMemberRepository = conversationMemberRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = resolveToken(accessor);
            if (StringUtils.hasText(token) && tokenProvider.validateToken(token)) {
                Long userId = tokenProvider.getUserIdFromJWT(token);
                UserDetails userDetails = userDetailsService.loadUserById(userId);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                accessor.setUser(auth);
            }
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        Matcher matcher = CONVERSATION_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return;
        }

        Long userId = resolveUserId(accessor.getUser());
        if (userId == null) {
            throw new MessagingException("Subscription denied");
        }

        Long conversationId;
        try {
            conversationId = Long.valueOf(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new MessagingException("Subscription denied");
        }

        boolean isActiveMember = conversationMemberRepository
                .existsByConversationIdAndUserIdAndDeletedAtIsNull(conversationId, userId);
        if (!isActiveMember) {
            throw new MessagingException("Subscription denied");
        }
    }

    private Long resolveUserId(Principal principal) {
        if (principal == null) {
            return null;
        }
        Object candidate = principal;
        if (principal instanceof Authentication authentication) {
            candidate = authentication.getPrincipal();
        }
        if (candidate instanceof UserPrincipal userPrincipal) {
            return userPrincipal.getId();
        }
        return null;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return accessor.getFirstNativeHeader("token");
    }
}
