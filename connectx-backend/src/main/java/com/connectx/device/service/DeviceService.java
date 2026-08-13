package com.connectx.device.service;

import com.connectx.common.exception.ApiException;
import com.connectx.device.dto.DeviceResponseDto;
import com.connectx.device.dto.RegisterDeviceDto;
import com.connectx.device.dto.UserPublicKeyDto;
import com.connectx.device.entity.Device;
import com.connectx.device.repository.DeviceRepository;
import com.connectx.user.entity.User;
import com.connectx.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    public DeviceService(DeviceRepository deviceRepository, UserRepository userRepository) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public DeviceResponseDto registerDevice(Long userId, RegisterDeviceDto dto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));

        Device device = new Device(user, dto.getDeviceName(), dto.getPublicKey(), dto.getKeyAlgorithm());
        Device savedDevice = deviceRepository.save(device);
        return DeviceResponseDto.fromEntity(savedDevice);
    }

    public List<DeviceResponseDto> getUserDevices(Long userId) {
        return deviceRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DeviceResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    public List<UserPublicKeyDto> getUserPublicKeys(Long userId) {
        List<Device> activeDevices = deviceRepository.findByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        if (activeDevices.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "No active cryptographic devices found for this user");
        }
        return activeDevices.stream()
                .map(d -> new UserPublicKeyDto(d.getId(), d.getUser().getId(), d.getDeviceName(), d.getPublicKey(), d.getKeyAlgorithm()))
                .collect(Collectors.toList());
    }

    @Transactional
    public void deactivateDevice(Long userId, Long deviceId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Device not found for this user"));
        device.setActive(false);
        deviceRepository.save(device);
    }

    @Transactional
    public void updateLastSeen(Long deviceId) {
        deviceRepository.findById(deviceId).ifPresent(device -> {
            device.setLastSeenAt(Instant.now());
            deviceRepository.save(device);
        });
    }

    @Transactional
    public DeviceResponseDto markDeviceSeenForUser(Long userId, Long deviceId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Device not found for this user"));
        device.setLastSeenAt(Instant.now());
        Device savedDevice = deviceRepository.save(device);
        return DeviceResponseDto.fromEntity(savedDevice);
    }
}
