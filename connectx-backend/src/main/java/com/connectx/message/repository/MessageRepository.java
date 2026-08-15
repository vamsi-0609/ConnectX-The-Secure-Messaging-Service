package com.connectx.message.repository;

import com.connectx.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId " +
           "AND m.deletedForEveryone = false " +
           "AND NOT EXISTS (SELECT 1 FROM MessageUserState mus WHERE mus.message.id = m.id AND mus.user.id = :userId) " +
           "AND (:clearedAfter IS NULL OR m.sentAt > :clearedAfter) " +
           "ORDER BY m.sentAt ASC")
    List<Message> findVisibleMessagesForUserInConversation(@Param("conversationId") Long conversationId,
                                                           @Param("userId") Long userId,
                                                           @Param("clearedAfter") Instant clearedAfter);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId " +
           "AND m.deletedForEveryone = false " +
           "AND NOT EXISTS (SELECT 1 FROM MessageUserState mus WHERE mus.message.id = m.id AND mus.user.id = :userId) " +
           "AND (:clearedAfter IS NULL OR m.sentAt > :clearedAfter) " +
           "ORDER BY m.sentAt DESC")
    List<Message> findLatestMessageInConversation(@Param("conversationId") Long conversationId,
                                                   @Param("userId") Long userId,
                                                   @Param("clearedAfter") Instant clearedAfter,
                                                   Pageable pageable);

    List<Message> findByConversationId(Long conversationId);

    /**
     * Fetch only messages sent by others (not currentUserId) in this conversation
     * that have not yet been marked read — to avoid loading the full history.
     */
    @Query("SELECT m FROM Message m " +
           "JOIN FETCH m.senderUser su " +
           "WHERE m.conversation.id = :conversationId " +
           "AND su.id <> :currentUserId " +
           "AND m.readAt IS NULL " +
           "AND m.deletedForEveryone = false")
    List<Message> findUnreadMessagesFromOthersInConversation(
            @Param("conversationId") Long conversationId,
            @Param("currentUserId") Long currentUserId);

    /**
     * Bulk-set delivered_at for all messages in a conversation not yet delivered.
     */
    @Modifying
    @Query("UPDATE Message m SET m.deliveredAt = :now " +
           "WHERE m.conversation.id = :conversationId " +
           "AND m.senderUser.id <> :currentUserId " +
           "AND m.deliveredAt IS NULL")
    int bulkMarkDeliveredInConversation(
            @Param("conversationId") Long conversationId,
            @Param("currentUserId") Long currentUserId,
            @Param("now") Instant now);

    void deleteByConversationId(Long conversationId);
}
