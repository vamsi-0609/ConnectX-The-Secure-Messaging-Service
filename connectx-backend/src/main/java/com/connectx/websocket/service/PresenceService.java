package com.connectx.websocket.service;

import com.connectx.common.security.UserPrincipal;
import com.connectx.conversation.repository.ConversationMemberRepository;
import com.connectx.user.repository.UserRepository;
import com.connectx.websocket.dto.WsEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks real online/offline presence from WebSocket session lifecycle events.
 * A user can have multiple concurrent sessions (tabs/devices) since the JWT/STOMP
 * principal is per-user, not per-device — a hand-rolled session counter (rather
 * than SimpUserRegistry) avoids any ambiguity about event-vs-registry ordering.
 */
@Component
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    // Absorbs quick reconnects (page refresh, brief network blips) without
    // flipping the indicator to OFFLINE and back — must be at least as long as
    // the frontend's own WS reconnect backoff.
    private static final long OFFLINE_GRACE_PERIOD_SECONDS = 8;

    private final UserRepository userRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "presence-offline-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, AtomicInteger> sessionCountByUserId = new ConcurrentHashMap<>();

    public PresenceService(UserRepository userRepository,
                            ConversationMemberRepository conversationMemberRepository,
                            SimpMessagingTemplate messagingTemplate) {
        this.userRepository = userRepository;
        this.conversationMemberRepository = conversationMemberRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        UserPrincipal principal = resolvePrincipal(event.getUser());
        if (principal == null) {
            return;
        }
        int count = sessionCountByUserId.computeIfAbsent(principal.getId(), k -> new AtomicInteger(0)).incrementAndGet();
        if (count == 1) {
            markOnline(principal.getId());
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        UserPrincipal principal = resolvePrincipal(event.getUser());
        if (principal == null) {
            return;
        }
        AtomicInteger counter = sessionCountByUserId.get(principal.getId());
        if (counter == null) {
            return;
        }
        int count = counter.decrementAndGet();
        if (count <= 0) {
            Long userId = principal.getId();
            scheduler.schedule(() -> maybeMarkOffline(userId), OFFLINE_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void maybeMarkOffline(Long userId) {
        AtomicInteger counter = sessionCountByUserId.get(userId);
        if (counter != null && counter.get() <= 0) {
            markOffline(userId);
        }
    }

    @Transactional
    void markOnline(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setStatus("ONLINE");
            userRepository.save(user);
            broadcastPresence(userId, user.getUsername(), "ONLINE", null);
        });
    }

    @Transactional
    void markOffline(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            Instant lastSeenAt = Instant.now();
            user.setStatus("OFFLINE");
            user.setLastSeenAt(lastSeenAt);
            userRepository.save(user);
            broadcastPresence(userId, user.getUsername(), "OFFLINE", lastSeenAt);
        });
    }

    private void broadcastPresence(Long userId, String username, String status, Instant lastSeenAt) {
        List<String> recipients = conversationMemberRepository.findDistinctOtherUsernamesSharingConversationWith(userId);
        if (recipients.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("status", status);
        if (lastSeenAt != null) {
            payload.put("lastSeenAt", lastSeenAt.toString());
        }
        WsEvent event = WsEvent.of("PRESENCE_UPDATE", payload);

        log.debug("Broadcasting presence update: userId={}, username={}, status={}, recipients={}",
                userId, username, status, recipients.size());
        recipients.forEach(recipientUsername ->
                messagingTemplate.convertAndSendToUser(recipientUsername, "/queue/messages", event));
    }

    private UserPrincipal resolvePrincipal(Principal principal) {
        if (principal == null) {
            return null;
        }
        if (principal instanceof Authentication authentication) {
            Object authenticatedPrincipal = authentication.getPrincipal();
            if (authenticatedPrincipal instanceof UserPrincipal userPrincipal) {
                return userPrincipal;
            }
        }
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        return null;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
