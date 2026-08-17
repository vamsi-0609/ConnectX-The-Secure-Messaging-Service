package com.connectx.connection.repository;

import com.connectx.connection.entity.ConnectionRequest;
import com.connectx.connection.entity.ConnectionRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConnectionRequestRepository extends JpaRepository<ConnectionRequest, Long> {

    boolean existsByRequesterIdAndRecipientIdAndStatus(Long requesterId, Long recipientId, ConnectionRequestStatus status);

    @Query("SELECT cr FROM ConnectionRequest cr JOIN FETCH cr.requester JOIN FETCH cr.recipient " +
           "WHERE cr.recipient.id = :recipientId AND cr.status = :status ORDER BY cr.createdAt DESC")
    List<ConnectionRequest> findByRecipientIdAndStatusWithUsers(@Param("recipientId") Long recipientId,
                                                                 @Param("status") ConnectionRequestStatus status);

    @Query("SELECT cr FROM ConnectionRequest cr JOIN FETCH cr.requester JOIN FETCH cr.recipient " +
           "WHERE cr.requester.id = :requesterId AND cr.status = :status ORDER BY cr.createdAt DESC")
    List<ConnectionRequest> findByRequesterIdAndStatusWithUsers(@Param("requesterId") Long requesterId,
                                                                 @Param("status") ConnectionRequestStatus status);
}
