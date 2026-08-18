package com.connectx.group.repository;

import com.connectx.group.entity.GroupMemberKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberKeyRepository extends JpaRepository<GroupMemberKey, Long> {

    // A member's current wrapped key -- the entire content of GET .../keys/mine (a future stage).
    Optional<GroupMemberKey> findByConversationIdAndMemberUserId(Long conversationId, Long memberUserId);

    // All rows for one group at one key version -- e.g. verifying a rotation wrapped a key for
    // every remaining member (a future stage).
    List<GroupMemberKey> findByConversationIdAndKeyVersion(Long conversationId, int keyVersion);

    // All current wrapped-key rows for a group. One row per active member by construction (unique
    // key on conversation_id + member_user_id, overwritten in place on rotation), so this doubles
    // as "every member who currently holds a key" -- no separate "current" filter is needed.
    List<GroupMemberKey> findByConversationId(Long conversationId);

    // Removes a departing/removed member's wrapped key (a future stage's rotation flow).
    void deleteByConversationIdAndMemberUserId(Long conversationId, Long memberUserId);
}
