package com.connectx.conversation.repository;

import com.connectx.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    @Query("SELECT c FROM Conversation c JOIN c.members m1 JOIN c.members m2 " +
           "WHERE c.type = com.connectx.conversation.entity.ConversationType.DIRECT " +
           "AND m1.user.id = :user1Id AND m2.user.id = :user2Id")
    Optional<Conversation> findDirectConversationBetweenUsers(@Param("user1Id") Long user1Id, @Param("user2Id") Long user2Id);

    @Query("SELECT DISTINCT c FROM Conversation c JOIN c.members m WHERE m.user.id = :userId AND m.deletedAt IS NULL ORDER BY c.updatedAt DESC")
    List<Conversation> findActiveConversationsForUser(@Param("userId") Long userId);
}
