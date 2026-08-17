package com.connectx.block.repository;

import com.connectx.block.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    // Single indexed existence check for "is there a block between these two users, in either
    // direction" -- the shape every new-relationship authorization boundary (DIRECT send,
    // connection request, connection accept) needs. Deliberately not two separate
    // existsByBlockerIdAndBlockedId calls: one query, not two, on every protected path.
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM UserBlock b " +
           "WHERE (b.blocker.id = :userA AND b.blocked.id = :userB) " +
           "OR (b.blocker.id = :userB AND b.blocked.id = :userA)")
    boolean existsEitherDirection(@Param("userA") Long userA, @Param("userB") Long userB);

    @Query("SELECT b FROM UserBlock b JOIN FETCH b.blocked WHERE b.blocker.id = :blockerId ORDER BY b.createdAt DESC")
    List<UserBlock> findAllByBlockerIdWithBlocked(@Param("blockerId") Long blockerId);
}
