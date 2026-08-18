package com.connectx.block.repository;

import com.connectx.block.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    // Batch either-direction check against a candidate set, for callers resolving many pairs at
    // once (e.g. profile-photo visibility across a whole conversation list) instead of one query
    // per candidate.
    @Query("SELECT CASE WHEN b.blocker.id = :userId THEN b.blocked.id ELSE b.blocker.id END FROM UserBlock b " +
           "WHERE (b.blocker.id = :userId AND b.blocked.id IN :otherIds) " +
           "OR (b.blocked.id = :userId AND b.blocker.id IN :otherIds)")
    List<Long> findBlockedEitherDirectionUserIds(@Param("userId") Long userId, @Param("otherIds") Collection<Long> otherIds);

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
