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

    @Query("SELECT cm FROM ConversationMember cm JOIN FETCH cm.user WHERE cm.conversation.id IN :conversationIds")
    List<ConversationMember> findByConversationIdInWithUsers(@Param("conversationIds") List<Long> conversationIds);

    boolean existsByConversationIdAndUserIdAndDeletedAtIsNull(Long conversationId, Long userId);

    long countByConversationIdAndDeletedAtIsNull(Long conversationId);

    long countByUserIdAndPinnedTrueAndDeletedAtIsNull(Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ConversationMember cm WHERE cm.conversation.id = :conversationId")
    void deleteByConversationId(@org.springframework.data.repository.query.Param("conversationId") Long conversationId);
}
