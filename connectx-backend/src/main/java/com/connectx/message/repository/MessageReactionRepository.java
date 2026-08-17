package com.connectx.message.repository;

import com.connectx.message.entity.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {

    @Query("SELECT r FROM MessageReaction r JOIN FETCH r.user WHERE r.message.id = :messageId ORDER BY r.createdAt ASC")
    List<MessageReaction> findByMessageIdWithUsers(@Param("messageId") Long messageId);

    @Query("SELECT r FROM MessageReaction r JOIN FETCH r.user WHERE r.message.id IN :messageIds ORDER BY r.createdAt ASC")
    List<MessageReaction> findByMessageIdInWithUsers(@Param("messageIds") List<Long> messageIds);

    Optional<MessageReaction> findByMessageIdAndUserId(Long messageId, Long userId);

    void deleteByMessageIdAndUserId(Long messageId, Long userId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MessageReaction r WHERE r.message.conversation.id = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
