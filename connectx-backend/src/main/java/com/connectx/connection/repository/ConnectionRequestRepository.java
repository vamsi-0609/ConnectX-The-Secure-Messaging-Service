package com.connectx.connection.repository;

import com.connectx.connection.entity.ConnectionRequest;
import com.connectx.connection.entity.ConnectionRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectionRequestRepository extends JpaRepository<ConnectionRequest, Long> {

    boolean existsByRequesterIdAndRecipientIdAndStatus(Long requesterId, Long recipientId, ConnectionRequestStatus status);

    // The pending-pair unique constraint (uk_connreq_pending_pair, a virtual generated column --
    // see V1__connection_and_group_schema.sql) guarantees at most one PENDING request can ever
    // exist between two users regardless of direction, so Optional is the correct return type.
    @Query("SELECT cr FROM ConnectionRequest cr WHERE cr.status = :status AND " +
           "((cr.requester.id = :userA AND cr.recipient.id = :userB) OR " +
           " (cr.requester.id = :userB AND cr.recipient.id = :userA))")
    Optional<ConnectionRequest> findBetweenUsersAndStatus(
            @Param("userA") Long userA,
            @Param("userB") Long userB,
            @Param("status") ConnectionRequestStatus status);

    @Query("SELECT cr FROM ConnectionRequest cr JOIN FETCH cr.requester JOIN FETCH cr.recipient " +
           "WHERE cr.recipient.id = :recipientId AND cr.status = :status ORDER BY cr.createdAt DESC")
    List<ConnectionRequest> findByRecipientIdAndStatusWithUsers(@Param("recipientId") Long recipientId,
                                                                 @Param("status") ConnectionRequestStatus status);

    @Query("SELECT cr FROM ConnectionRequest cr JOIN FETCH cr.requester JOIN FETCH cr.recipient " +
           "WHERE cr.requester.id = :requesterId AND cr.status = :status ORDER BY cr.createdAt DESC")
    List<ConnectionRequest> findByRequesterIdAndStatusWithUsers(@Param("requesterId") Long requesterId,
                                                                 @Param("status") ConnectionRequestStatus status);
}
