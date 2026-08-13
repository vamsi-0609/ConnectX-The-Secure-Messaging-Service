package com.connectx.message.repository;

import com.connectx.message.entity.MessageUserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MessageUserStateRepository extends JpaRepository<MessageUserState, Long> {

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    Optional<MessageUserState> findByMessageIdAndUserId(Long messageId, Long userId);

    void deleteByMessageId(Long messageId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM MessageUserState mus WHERE mus.message.conversation.id = :conversationId")
    void deleteByConversationId(@org.springframework.data.repository.query.Param("conversationId") Long conversationId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM MessageUserState mus WHERE mus.message.conversation.id = :conversationId AND mus.user.id = :userId")
    void deleteByConversationIdAndUserId(
            @org.springframework.data.repository.query.Param("conversationId") Long conversationId,
            @org.springframework.data.repository.query.Param("userId") Long userId);
}
