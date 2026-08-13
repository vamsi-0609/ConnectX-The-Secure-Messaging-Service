package com.connectx.device.dto;

import com.connectx.device.entity.Device;
import java.time.Instant;

public class DeviceResponseDto {

    private Long id;
    private Long userId;
    private String deviceName;
    private String publicKey;
    private String keyAlgorithm;
    private Instant createdAt;
    private Instant lastSeenAt;
    private boolean active;

    public DeviceResponseDto() {}

    public static DeviceResponseDto fromEntity(Device device) {
        DeviceResponseDto dto = new DeviceResponseDto();
        dto.setId(device.getId());
        dto.setUserId(device.getUser().getId());
        dto.setDeviceName(device.getDeviceName());
        dto.setPublicKey(device.getPublicKey());
        dto.setKeyAlgorithm(device.getKeyAlgorithm());
        dto.setCreatedAt(device.getCreatedAt());
        dto.setLastSeenAt(device.getLastSeenAt());
        dto.setActive(device.isActive());
        return dto;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getKeyAlgorithm() {
        return keyAlgorithm;
    }

    public void setKeyAlgorithm(String keyAlgorithm) {
        this.keyAlgorithm = keyAlgorithm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
