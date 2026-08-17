package com.connectx.block.service;

import com.connectx.block.dto.UserBlockDto;
import com.connectx.block.entity.UserBlock;
import com.connectx.block.repository.UserBlockRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BlockService {

    private static final Logger log = LoggerFactory.getLogger(BlockService.class);

    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    // Self-injected proxy so insertBlockInNewTransaction below actually runs through Spring's
    // transactional AOP proxy when invoked from within this class -- the same REQUIRES_NEW +
    // DataIntegrityViolationException-catch pattern already proven in ConnectionService
    // (insertConnectionRequestInNewTransaction) and MessageService (reaction/star inserts).
    private final BlockService self;

    public BlockService(UserBlockRepository userBlockRepository,
                         UserRepository userRepository,
                         @Lazy BlockService self) {
        this.userBlockRepository = userBlockRepository;
        this.userRepository = userRepository;
        this.self = self;
    }

    // READ_COMMITTED (not the MySQL default REPEATABLE READ): on the losing side of a race, the
    // catch block below re-reads user_blocks for the same pair right after a REQUIRES_NEW insert
    // on a genuinely separate, already-committed transaction (the winner). Under REPEATABLE READ
    // this method's earlier plain SELECT (the idempotency pre-check above) already fixed a
    // consistent-read snapshot from before that commit, so the re-read would miss it and this
    // method would incorrectly throw instead of returning the now-existing block -- the same
    // cross-transaction visibility gap already documented and fixed this way for
    // MessageService#addOrUpdateReaction and ConversationService#createOrGetDirectConversation.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserBlockDto blockUser(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_BLOCK_SELF", "Cannot block yourself");
        }

        User blocker = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Current user not found"));
        User blocked = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));

        // Idempotent: re-blocking an already-blocked user is a safe no-op that returns the
        // existing block, not an error -- matches this codebase's existing idempotency
        // convention for repeat actions (e.g. ConversationService#deleteConversationForUser).
        UserBlock existing = userBlockRepository.findByBlockerIdAndBlockedId(currentUserId, targetUserId).orElse(null);
        if (existing != null) {
            return UserBlockDto.fromEntity(existing);
        }

        try {
            UserBlock saved = self.insertBlockInNewTransaction(blocker, blocked);
            return UserBlockDto.fromEntity(saved);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent identical block insert (uk_user_blocks_pair) -- the
            // winner's row is already committed, which is exactly the desired end state.
            log.debug("Duplicate block insert lost race: blockerId={}, blockedId={}", currentUserId, targetUserId);
            return userBlockRepository.findByBlockerIdAndBlockedId(currentUserId, targetUserId)
                    .map(UserBlockDto::fromEntity)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserBlock insertBlockInNewTransaction(User blocker, User blocked) {
        return userBlockRepository.saveAndFlush(new UserBlock(blocker, blocked));
    }

    @Transactional
    public void unblockUser(Long currentUserId, Long targetUserId) {
        // Scoped to blockerId = currentUserId by construction -- there is no query path here
        // that could ever locate, let alone remove, a block belonging to a different user, which
        // is what makes forged unblock attempts structurally impossible rather than merely
        // permission-checked.
        userBlockRepository.findByBlockerIdAndBlockedId(currentUserId, targetUserId)
                .ifPresentOrElse(
                        userBlockRepository::delete,
                        () -> log.debug("Unblock no-op, no existing block: blockerId={}, blockedId={}", currentUserId, targetUserId)
                );
    }

    @Transactional(readOnly = true)
    public List<UserBlockDto> getMyBlocks(Long currentUserId) {
        return userBlockRepository.findAllByBlockerIdWithBlocked(currentUserId).stream()
                .map(UserBlockDto::fromEntity)
                .collect(Collectors.toList());
    }
}
