package com.connectx.media.repository;

import com.connectx.media.entity.MessageMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageMediaRepository extends JpaRepository<MessageMedia, Long> {

    Optional<MessageMedia> findByIdAndConversationId(Long id, Long conversationId);

    List<MessageMedia> findByConversationId(Long conversationId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM MessageMedia mm WHERE mm.conversation.id = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
