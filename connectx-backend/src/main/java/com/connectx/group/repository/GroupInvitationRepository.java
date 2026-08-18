package com.connectx.group.repository;

import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface GroupInvitationRepository extends JpaRepository<GroupInvitation, Long> {

    boolean existsByGroupIdAndInviteeIdAndStatus(Long groupId, Long inviteeId, GroupInvitationStatus status);

    @Query("SELECT gi FROM GroupInvitation gi JOIN FETCH gi.invitee JOIN FETCH gi.invitedBy " +
           "WHERE gi.invitee.id = :inviteeId AND gi.status = :status ORDER BY gi.createdAt DESC")
    List<GroupInvitation> findByInviteeIdAndStatusWithUsers(@Param("inviteeId") Long inviteeId,
                                                              @Param("status") GroupInvitationStatus status);

    @Query("SELECT gi FROM GroupInvitation gi JOIN FETCH gi.invitee JOIN FETCH gi.invitedBy " +
           "WHERE gi.invitedBy.id = :invitedByUserId AND gi.status = :status ORDER BY gi.createdAt DESC")
    List<GroupInvitation> findByInvitedByIdAndStatusWithUsers(@Param("invitedByUserId") Long invitedByUserId,
                                                                @Param("status") GroupInvitationStatus status);

    // Groups Stage 3: called from GroupService#removeMember/leaveGroup so a stale PENDING
    // invitation (created before -- or racing with -- the membership that just ended) can never be
    // used to bypass a removal/leave decision by simply accepting it afterward; see
    // GroupService's javadoc on this call site. A single bulk UPDATE, not a per-row loop.
    @Modifying
    @Query("UPDATE GroupInvitation gi SET gi.status = com.connectx.group.entity.GroupInvitationStatus.CANCELLED, gi.respondedAt = :respondedAt " +
           "WHERE gi.group.id = :groupId AND gi.invitee.id = :inviteeId AND gi.status = com.connectx.group.entity.GroupInvitationStatus.PENDING")
    int cancelAllPendingForGroupAndInvitee(@Param("groupId") Long groupId, @Param("inviteeId") Long inviteeId, @Param("respondedAt") Instant respondedAt);
}
