package com.connectx.message.repository;

import com.connectx.message.entity.MessageStar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageStarRepository extends JpaRepository<MessageStar, Long> {

    Optional<MessageStar> findByMessageIdAndUserId(Long messageId, Long userId);

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    @Query("SELECT ms.message.id FROM MessageStar ms WHERE ms.user.id = :userId AND ms.message.id IN :messageIds")
    List<Long> findStarredMessageIds(@Param("userId") Long userId, @Param("messageIds") List<Long> messageIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MessageStar ms WHERE ms.message.id = :messageId")
    void deleteByMessageId(@Param("messageId") Long messageId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MessageStar ms WHERE ms.message.conversation.id = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
