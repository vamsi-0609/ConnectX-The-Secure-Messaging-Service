package com.connectx.user.service;

import com.connectx.common.exception.ApiException;
import com.connectx.user.dto.UserDto;
import com.connectx.user.dto.UserProfileUpdateDto;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import com.connectx.user.storage.ProfileImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ProfileImageStorage profileImageStorage;

    public UserService(UserRepository userRepository, ProfileImageStorage profileImageStorage) {
        this.userRepository = userRepository;
        this.profileImageStorage = profileImageStorage;
    }

    public UserDto getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        return UserDto.fromEntity(user);
    }

    public List<UserDto> searchUsersByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return List.of();
        }
        return userRepository.findByUsernameContainingIgnoreCase(username.trim())
                .stream()
                .map(UserDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public UserDto updateUserProfile(Long userId, UserProfileUpdateDto updateDto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        if (updateDto.getDisplayName() != null && !updateDto.getDisplayName().trim().isEmpty()) {
            user.setDisplayName(updateDto.getDisplayName().trim());
        }
        if (updateDto.getProfileImageUrl() != null) {
            user.setProfileImageUrl(updateDto.getProfileImageUrl());
        }

        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional
    public UserDto uploadProfilePhoto(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        String publicPath = profileImageStorage.store(userId, file);
        user.setProfileImageUrl(publicPath + "?v=" + System.currentTimeMillis());
        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    @Transactional
    public UserDto removeProfilePhoto(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        profileImageStorage.delete(userId);
        user.setProfileImageUrl(null);
        User updatedUser = userRepository.save(user);
        return UserDto.fromEntity(updatedUser);
    }

    public com.connectx.user.dto.UserIdentityKeyDto getIdentityKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
        return new com.connectx.user.dto.UserIdentityKeyDto(user.getMasterPublicKey(), user.getMasterPrivateKey());
    }

    @Transactional
    public com.connectx.user.dto.UserIdentityKeyDto saveIdentityKey(Long userId, com.connectx.user.dto.UserIdentityKeyDto dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));

        if (dto.getMasterPublicKey() != null && !dto.getMasterPublicKey().isBlank()) {
            user.setMasterPublicKey(dto.getMasterPublicKey());
        }
        if (dto.getMasterPrivateKey() != null && !dto.getMasterPrivateKey().isBlank()) {
            user.setMasterPrivateKey(dto.getMasterPrivateKey());
        }
        userRepository.save(user);
        return new com.connectx.user.dto.UserIdentityKeyDto(user.getMasterPublicKey(), user.getMasterPrivateKey());
    }
}
