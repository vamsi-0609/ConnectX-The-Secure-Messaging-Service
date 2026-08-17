package com.connectx.user.repository;

import com.connectx.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<User> findByUsernameContainingIgnoreCase(String username, Pageable pageable);

    // Search privacy: excludes any user with a block in EITHER direction against the searcher, at
    // the database level via a NOT EXISTS anti-join against UserBlock -- one query, no per-result
    // block lookups and no loading the block table into memory. UserBlock lives in a different
    // package (com.connectx.block.entity), which is fine for JPQL: it resolves entity names from
    // the whole persistence unit, not Java imports -- the same cross-module coupling this codebase
    // already has at the service layer (ConnectionService/ConversationService both depend directly
    // on UserBlockRepository).
    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :username, '%')) " +
           "AND NOT EXISTS (" +
           "  SELECT 1 FROM UserBlock b WHERE " +
           "  (b.blocker.id = :currentUserId AND b.blocked.id = u.id) OR " +
           "  (b.blocker.id = u.id AND b.blocked.id = :currentUserId)" +
           ")")
    List<User> searchByUsernameExcludingBlockedPairs(
            @Param("username") String username,
            @Param("currentUserId") Long currentUserId,
            Pageable pageable);

    // Row-level lock used only to serialize concurrent createOrGetDirectConversation calls for
    // the same user pair (see ConversationService) -- no schema change required, this just takes
    // a SELECT ... FOR UPDATE on the users table's existing primary key.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
