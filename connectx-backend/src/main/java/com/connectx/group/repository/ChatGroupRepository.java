package com.connectx.group.repository;

import com.connectx.group.entity.ChatGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChatGroupRepository extends JpaRepository<ChatGroup, Long> {
    // conversationId doubles as ChatGroup's own @Id (see ChatGroup's @MapsId), so the inherited
    // findById(Long) already looks a group up by its conversation id -- no custom query needed.

    // Groups Stage 2: serializes every membership-modifying operation for a given group (direct
    // add, invitation accept) against every other concurrent one for the SAME group, exactly the
    // same SELECT ... FOR UPDATE technique UserRepository#findByIdForUpdate already uses to
    // serialize concurrent DIRECT-conversation creation for a pair. Held for the remainder of the
    // caller's transaction, covering the active-member-count re-check and the insert/restore
    // itself -- see GroupService#addMember.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM ChatGroup g WHERE g.conversationId = :conversationId")
    Optional<ChatGroup> findByIdForUpdate(@Param("conversationId") Long conversationId);
}
