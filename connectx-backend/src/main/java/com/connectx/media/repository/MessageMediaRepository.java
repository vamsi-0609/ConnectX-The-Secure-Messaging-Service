package com.connectx.media.repository;

import com.connectx.media.entity.MessageMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MessageMediaRepository extends JpaRepository<MessageMedia, Long> {

    Optional<MessageMedia> findByIdAndConversationId(Long id, Long conversationId);
}
