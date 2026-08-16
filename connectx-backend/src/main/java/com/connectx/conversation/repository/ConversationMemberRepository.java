package com.connectx.conversation.repository;

import com.connectx.conversation.entity.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    Optional<ConversationMember> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ConversationMember> findByConversationId(Long conversationId);

    @Query("SELECT cm FROM ConversationMember cm JOIN FETCH cm.user WHERE cm.conversation.id = :conversationId")
    List<ConversationMember> findByConversationIdWithUsers(@Param("conversationId") Long conversationId);

    // For real-time broadcast fan-out only (edit/delete/pin/reaction) — unlike
    // findByConversationIdWithUsers above, this excludes members who've soft-deleted the
    // conversation for themselves, so they don't keep receiving live events for a chat
    // their own client believes no longer exists. Message sends intentionally keep using
    // the unfiltered version, since they need to see deleted members to decide whether to
    // restore the conversation's visibility for them.
    @Query("SELECT cm FROM ConversationMember cm JOIN FETCH cm.user WHERE cm.conversation.id = :conversationId AND cm.deletedAt IS NULL")
    List<ConversationMember> findByConversationIdAndDeletedAtIsNullWithUsers(@Param("conversationId") Long conversationId);

    @Query("SELECT cm FROM ConversationMember cm JOIN FETCH cm.user WHERE cm.conversation.id IN :conversationIds")
    List<ConversationMember> findByConversationIdInWithUsers(@Param("conversationIds") List<Long> conversationIds);

    boolean existsByConversationIdAndUserIdAndDeletedAtIsNull(Long conversationId, Long userId);

    long countByConversationIdAndDeletedAtIsNull(Long conversationId);

    long countByUserIdAndPinnedTrueAndDeletedAtIsNull(Long userId);

    @Query("SELECT DISTINCT m2.user.username FROM ConversationMember m1 JOIN m1.conversation.members m2 " +
           "WHERE m1.user.id = :userId AND m1.deletedAt IS NULL AND m2.deletedAt IS NULL AND m2.user.id <> :userId")
    List<String> findDistinctOtherUsernamesSharingConversationWith(@Param("userId") Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ConversationMember cm WHERE cm.conversation.id = :conversationId")
    void deleteByConversationId(@org.springframework.data.repository.query.Param("conversationId") Long conversationId);
}
