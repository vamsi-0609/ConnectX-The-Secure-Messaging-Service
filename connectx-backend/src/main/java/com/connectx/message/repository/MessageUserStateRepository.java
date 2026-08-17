package com.connectx.message.repository;

import com.connectx.message.entity.MessageUserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface MessageUserStateRepository extends JpaRepository<MessageUserState, Long> {

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    Optional<MessageUserState> findByMessageIdAndUserId(Long messageId, Long userId);

    void deleteByMessageId(Long messageId);

    /**
     * Bulk-marks every message in a conversation as deleted-for-this-user in a single
     * INSERT ... SELECT, replacing what used to be an exists-check + insert issued once per
     * message (M-05: could be 100+ round trips for a single "delete conversation" click).
     * Native SQL (rather than HQL bulk-insert) because the target table uses an
     * IDENTITY-generated id, which HQL's `insert into ... select` doesn't handle -- omitting
     * the id column here lets the database assign it per inserted row, same as a normal insert.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            value = "INSERT INTO message_user_state (message_id, user_id, deleted_at) " +
                    "SELECT m.id, :userId, :now FROM messages m " +
                    "WHERE m.conversation_id = :conversationId " +
                    "AND NOT EXISTS (SELECT 1 FROM message_user_state mus WHERE mus.message_id = m.id AND mus.user_id = :userId)",
            nativeQuery = true)
    int bulkInsertDeletedStateForConversation(
            @org.springframework.data.repository.query.Param("conversationId") Long conversationId,
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("now") Instant now);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM MessageUserState mus WHERE mus.message.conversation.id = :conversationId")
    void deleteByConversationId(@org.springframework.data.repository.query.Param("conversationId") Long conversationId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM MessageUserState mus WHERE mus.message.conversation.id = :conversationId AND mus.user.id = :userId")
    void deleteByConversationIdAndUserId(
            @org.springframework.data.repository.query.Param("conversationId") Long conversationId,
            @org.springframework.data.repository.query.Param("userId") Long userId);
}
