package com.connectx.websocket.controller;

import com.connectx.common.security.UserPrincipal;
import com.connectx.message.dto.MessageDto;
import com.connectx.message.dto.SendMessageRequestDto;
import com.connectx.message.service.MessageService;
import com.connectx.websocket.dto.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

@Controller
public class WebSocketMessageController {

    private static final Logger log = LoggerFactory.getLogger(WebSocketMessageController.class);

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketMessageController(MessageService messageService,
                                       SimpMessagingTemplate messagingTemplate) {
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/message.send")
    public void handleSendMessage(@Payload WsEvent event, Principal principal) {
        Long currentUserId = resolveCurrentUserId(principal);
        if (currentUserId == null) {
            log.warn("Unauthenticated WebSocket message send attempt");
            return;
        }

        Map<String, Object> payload = event.getPayload();

        Long conversationId = ((Number) payload.get("conversationId")).longValue();
        String ciphertext = (String) payload.get("ciphertext");
        String nonce = (String) payload.get("nonce");
        String encryptionAlgorithm = (String) payload.get("encryptionAlgorithm");
        log.debug("[WS RECEIVE] messageType={} ciphertextLength={}", event.getType(), ciphertext != null ? ciphertext.length() : 0);

        SendMessageRequestDto sendDto = new SendMessageRequestDto();
        sendDto.setConversationId(conversationId);
        sendDto.setCiphertext(ciphertext);
        sendDto.setNonce(nonce);
        sendDto.setEncryptionAlgorithm(encryptionAlgorithm);
        if (payload.containsKey("senderDeviceId")) {
            sendDto.setSenderDeviceId(((Number) payload.get("senderDeviceId")).longValue());
        }
        if (payload.containsKey("recipientDeviceId")) {
            sendDto.setRecipientDeviceId(((Number) payload.get("recipientDeviceId")).longValue());
        }

        MessageDto savedMessage = messageService.sendMessage(currentUserId, sendDto);
        log.debug("[DB SAVE] messageId={} ciphertextLength={}", savedMessage.getId(), savedMessage.getCiphertext() != null ? savedMessage.getCiphertext().length() : 0);

        // Send MESSAGE_ACK back to sender
        Map<String, Object> ackPayload = new HashMap<>();
        ackPayload.put("messageId", savedMessage.getId());
        ackPayload.put("conversationId", conversationId);
        ackPayload.put("sentAt", savedMessage.getSentAt().toString());

        WsEvent ackEvent = WsEvent.of("MESSAGE_ACK", event.getRequestId(), ackPayload);
        UserPrincipal sender = resolveCurrentUser(principal);
        if (sender != null) {
            messagingTemplate.convertAndSendToUser(sender.getUsername(), "/queue/acks", ackEvent);
        }
    }

    private Long resolveCurrentUserId(Principal principal) {
        UserPrincipal user = resolveCurrentUser(principal);
        return user != null ? user.getId() : null;
    }

    private UserPrincipal resolveCurrentUser(Principal principal) {
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
        log.warn("Unexpected WebSocket principal type: {}", principal.getClass().getName());
        return null;
    }

    @MessageMapping("/message.delivered")
    public void handleMessageDelivered(@Payload WsEvent event, Principal principal) {
        if (event.getPayload() != null && event.getPayload().containsKey("messageId")) {
            Long messageId = ((Number) event.getPayload().get("messageId")).longValue();
            messageService.markDelivered(messageId);
        }
    }

    @MessageMapping("/message.read")
    public void handleMessageRead(@Payload WsEvent event, Principal principal) {
        if (event.getPayload() != null && event.getPayload().containsKey("messageId")) {
            Long messageId = ((Number) event.getPayload().get("messageId")).longValue();
            messageService.markRead(messageId);
        }
    }
}
