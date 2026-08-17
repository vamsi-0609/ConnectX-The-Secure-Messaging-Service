package com.connectx.connection.service;

import com.connectx.block.repository.UserBlockRepository;
import com.connectx.common.exception.ApiException;
import com.connectx.connection.dto.ConnectionRequestDto;
import com.connectx.connection.dto.SendConnectionRequestDto;
import com.connectx.connection.dto.UserConnectionDto;
import com.connectx.connection.entity.ConnectionRequest;
import com.connectx.connection.entity.ConnectionRequestStatus;
import com.connectx.connection.entity.ConnectionSource;
import com.connectx.connection.entity.UserConnection;
import com.connectx.connection.repository.ConnectionRequestRepository;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final ConnectionRequestRepository connectionRequestRepository;
    private final UserConnectionRepository userConnectionRepository;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    // Self-injected proxy so the REQUIRES_NEW methods below actually run through Spring's
    // transactional AOP proxy when invoked from within this class -- the same pattern already
    // proven in MessageService for the reaction/star insert races (see
    // MessageService#insertReactionInNewTransaction for the detailed rationale: catching
    // DataIntegrityViolationException from a plain saveAndFlush in the SAME transaction still
    // leaves that transaction marked rollback-only, so the racing insert must run in a genuinely
    // separate transaction for the caller to recover cleanly).
    private final ConnectionService self;

    public ConnectionService(ConnectionRequestRepository connectionRequestRepository,
                              UserConnectionRepository userConnectionRepository,
                              UserRepository userRepository,
                              UserBlockRepository userBlockRepository,
                              @Lazy ConnectionService self) {
        this.connectionRequestRepository = connectionRequestRepository;
        this.userConnectionRepository = userConnectionRepository;
        this.userRepository = userRepository;
        this.userBlockRepository = userBlockRepository;
        this.self = self;
    }

    @Transactional
    public ConnectionRequestDto sendRequest(Long currentUserId, SendConnectionRequestDto dto) {
        Long recipientId = dto.getRecipientId();

        if (currentUserId.equals(recipientId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_REQUEST", "Cannot send a connection request to yourself");
        }

        User requester = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Current user not found"));
        User recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Target user not found"));

        // A block is directional in storage but bilateral for new-relationship purposes: neither
        // side may initiate a connection request while a block exists in either direction. Checked
        // before ALREADY_CONNECTED/REQUEST_ALREADY_PENDING so blocking takes precedence over any
        // pre-existing relationship state, per the Stage 2 blocking-precedence requirement.
        if (userBlockRepository.existsEitherDirection(currentUserId, recipientId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "You cannot send a connection request to this user");
        }

        Long low = Math.min(currentUserId, recipientId);
        Long high = Math.max(currentUserId, recipientId);
        if (userConnectionRepository.existsByUserLowIdAndUserHighId(low, high)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CONNECTED", "You are already connected with this user");
        }

        // A pending request already exists in either direction -- the correct next action is to
        // respond to that one, not create a second, redundant request. Checked both directions
        // since "recipient later requests the still-pending requester back" must be handled the
        // same way as an exact duplicate.
        if (connectionRequestRepository.existsByRequesterIdAndRecipientIdAndStatus(currentUserId, recipientId, ConnectionRequestStatus.PENDING)
                || connectionRequestRepository.existsByRequesterIdAndRecipientIdAndStatus(recipientId, currentUserId, ConnectionRequestStatus.PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ALREADY_PENDING",
                    "A pending connection request already exists between you and this user");
        }

        try {
            ConnectionRequest saved = self.insertConnectionRequestInNewTransaction(requester, recipient);
            return ConnectionRequestDto.fromEntity(saved);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request for the same ordered pair (uk_connreq_pending_pair).
            log.debug("Duplicate pending connection request lost race: requesterId={}, recipientId={}", currentUserId, recipientId);
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ALREADY_PENDING",
                    "A pending connection request already exists between you and this user");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConnectionRequest insertConnectionRequestInNewTransaction(User requester, User recipient) {
        return connectionRequestRepository.saveAndFlush(new ConnectionRequest(requester, recipient));
    }

    @Transactional(readOnly = true)
    public List<ConnectionRequestDto> getPendingIncomingRequests(Long currentUserId) {
        return connectionRequestRepository
                .findByRecipientIdAndStatusWithUsers(currentUserId, ConnectionRequestStatus.PENDING)
                .stream()
                .map(ConnectionRequestDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ConnectionRequestDto> getSentOutgoingRequests(Long currentUserId) {
        return connectionRequestRepository
                .findByRequesterIdAndStatusWithUsers(currentUserId, ConnectionRequestStatus.PENDING)
                .stream()
                .map(ConnectionRequestDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public ConnectionRequestDto acceptRequest(Long currentUserId, Long requestId) {
        ConnectionRequest request = connectionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Connection request not found"));

        if (!request.getRecipient().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the recipient can accept this request");
        }
        if (request.getStatus() != ConnectionRequestStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NOT_PENDING", "This request is no longer pending");
        }
        // A block created after the request was sent (by either party, in either direction) must
        // still prevent the connection from being formed at accept time -- required so a stale
        // PENDING request can't be used to bypass a block that didn't exist yet when it was sent.
        if (userBlockRepository.existsEitherDirection(request.getRequester().getId(), request.getRecipient().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BLOCKED", "This request cannot be accepted");
        }

        request.setStatus(ConnectionRequestStatus.ACCEPTED);
        request.setRespondedAt(Instant.now());
        connectionRequestRepository.save(request);

        Long requesterId = request.getRequester().getId();
        Long recipientId = request.getRecipient().getId();
        Long low = Math.min(requesterId, recipientId);
        Long high = Math.max(requesterId, recipientId);

        if (!userConnectionRepository.existsByUserLowIdAndUserHighId(low, high)) {
            User userLow = low.equals(requesterId) ? request.getRequester() : request.getRecipient();
            User userHigh = high.equals(requesterId) ? request.getRequester() : request.getRecipient();
            try {
                self.insertConnectionInNewTransaction(userLow, userHigh);
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent accept for the same pair (uk_connections_pair) --
                // the connection already exists from the winner, which is exactly the desired
                // end state, so there is nothing left to reconcile.
                log.debug("Duplicate connection insert lost race: userIdLow={}, userIdHigh={}", low, high);
            }
        }

        return ConnectionRequestDto.fromEntity(request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserConnection insertConnectionInNewTransaction(User userLow, User userHigh) {
        return userConnectionRepository.saveAndFlush(new UserConnection(userLow, userHigh, ConnectionSource.REQUEST));
    }

    @Transactional
    public ConnectionRequestDto rejectRequest(Long currentUserId, Long requestId) {
        ConnectionRequest request = connectionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Connection request not found"));

        if (!request.getRecipient().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the recipient can reject this request");
        }
        if (request.getStatus() != ConnectionRequestStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NOT_PENDING", "This request is no longer pending");
        }

        request.setStatus(ConnectionRequestStatus.REJECTED);
        request.setRespondedAt(Instant.now());
        connectionRequestRepository.save(request);
        return ConnectionRequestDto.fromEntity(request);
    }

    @Transactional
    public ConnectionRequestDto cancelRequest(Long currentUserId, Long requestId) {
        ConnectionRequest request = connectionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Connection request not found"));

        if (!request.getRequester().getId().equals(currentUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the requester can cancel this request");
        }
        if (request.getStatus() != ConnectionRequestStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NOT_PENDING", "This request is no longer pending");
        }

        request.setStatus(ConnectionRequestStatus.CANCELLED);
        request.setRespondedAt(Instant.now());
        connectionRequestRepository.save(request);
        return ConnectionRequestDto.fromEntity(request);
    }

    @Transactional(readOnly = true)
    public List<UserConnectionDto> getMyConnections(Long currentUserId) {
        return userConnectionRepository.findAllForUserWithUsers(currentUserId).stream()
                .map(c -> UserConnectionDto.fromEntity(c, currentUserId))
                .collect(Collectors.toList());
    }

    // Deletes only the UserConnection row for this pair -- never touches Conversation,
    // ConversationMember, Message, or block rows. A plain find-then-delete (no REQUIRES_NEW
    // self-proxy, unlike sendRequest/acceptRequest above) is sufficient here because a DELETE has
    // no unique-constraint race to recover from the way concurrent INSERTs do: on a concurrent
    // double-removal, whichever request's transaction commits first deletes the row, and the
    // other's findByUserLowIdAndUserHighId simply comes up empty, landing on the same
    // CONNECTION_NOT_FOUND path as removing an already-nonexistent connection.
    @Transactional
    public void removeConnection(Long currentUserId, Long otherUserId) {
        if (currentUserId.equals(otherUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_REMOVE_SELF", "Cannot remove a connection with yourself");
        }

        Long low = Math.min(currentUserId, otherUserId);
        Long high = Math.max(currentUserId, otherUserId);

        UserConnection connection = userConnectionRepository.findByUserLowIdAndUserHighId(low, high)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND",
                        "You are not connected with this user"));

        userConnectionRepository.delete(connection);
    }
}
