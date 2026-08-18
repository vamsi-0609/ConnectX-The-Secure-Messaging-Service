package com.connectx.user.service;

import com.connectx.block.repository.UserBlockRepository;
import com.connectx.connection.repository.UserConnectionRepository;
import com.connectx.user.entity.User;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single authority for "can this viewer see this user's profile photo" -- backs both the DTO
 * fields (UserDto, ConversationMemberDto, ConnectionRequestDto) and the raw image-serving
 * endpoint (ProfileImageController), which is the actual enforcement boundary since the DTO
 * field alone can't stop someone fetching the image URL directly.
 *
 * Order of checks mirrors every other relationship boundary in this codebase (ConnectionService,
 * ConversationService, MessageService): a block always wins, checked first and unconditionally.
 */
@Service
public class ProfileVisibilityService {

    public static final String VISIBILITY_EVERYONE = "EVERYONE";
    public static final String VISIBILITY_CONNECTIONS = "CONNECTIONS";

    private final UserBlockRepository userBlockRepository;
    private final UserConnectionRepository userConnectionRepository;

    public ProfileVisibilityService(UserBlockRepository userBlockRepository,
                                     UserConnectionRepository userConnectionRepository) {
        this.userBlockRepository = userBlockRepository;
        this.userConnectionRepository = userConnectionRepository;
    }

    public boolean isProfilePhotoVisible(User owner, Long viewerId) {
        return resolvePhotoVisibility(viewerId, List.of(owner)).getOrDefault(owner.getId(), false);
    }

    /**
     * Batch variant: resolves visibility for many candidate owners against one viewer in a fixed
     * two extra queries total instead of one (or two) per owner -- same batching precedent as
     * ConversationService's M-04 latest-message fix, needed here so the conversation list and
     * user search endpoints don't turn into an N+1 query storm.
     */
    public Map<Long, Boolean> resolvePhotoVisibility(Long viewerId, Collection<User> owners) {
        Map<Long, Boolean> result = new HashMap<>();
        if (viewerId == null) {
            owners.forEach(o -> result.put(o.getId(), false));
            return result;
        }

        List<User> needsCheck = new ArrayList<>();
        for (User owner : owners) {
            if (owner.getId().equals(viewerId)) {
                result.put(owner.getId(), true);
            } else {
                needsCheck.add(owner);
            }
        }
        if (needsCheck.isEmpty()) {
            return result;
        }

        Set<Long> candidateIds = needsCheck.stream().map(User::getId).collect(Collectors.toSet());
        Set<Long> blockedIds = new HashSet<>(userBlockRepository.findBlockedEitherDirectionUserIds(viewerId, candidateIds));
        Set<Long> connectedIds = new HashSet<>(userConnectionRepository.findConnectedUserIds(viewerId));

        for (User owner : needsCheck) {
            boolean visible;
            if (blockedIds.contains(owner.getId())) {
                visible = false;
            } else if (VISIBILITY_CONNECTIONS.equals(owner.getProfilePhotoVisibility())) {
                visible = connectedIds.contains(owner.getId());
            } else {
                // null (pre-existing user, column never backfilled) or explicit EVERYONE
                visible = true;
            }
            result.put(owner.getId(), visible);
        }
        return result;
    }
}
