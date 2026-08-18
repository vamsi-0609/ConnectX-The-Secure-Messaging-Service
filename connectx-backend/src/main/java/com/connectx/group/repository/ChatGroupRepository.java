package com.connectx.group.repository;

import com.connectx.group.entity.ChatGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatGroupRepository extends JpaRepository<ChatGroup, Long> {
    // conversationId doubles as ChatGroup's own @Id (see ChatGroup's @MapsId), so the inherited
    // findById(Long) already looks a group up by its conversation id -- no custom query needed.
}
