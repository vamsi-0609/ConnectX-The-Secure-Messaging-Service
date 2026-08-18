package com.connectx.group.repository;

import com.connectx.group.entity.GroupInvitation;
import com.connectx.group.entity.GroupInvitationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
