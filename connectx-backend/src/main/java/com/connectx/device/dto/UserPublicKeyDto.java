package com.connectx.device.dto;

public class UserPublicKeyDto {

    private Long deviceId;
    private Long userId;
    private String deviceName;
    private String publicKey;
    private String keyAlgorithm;

    public UserPublicKeyDto() {}

    public UserPublicKeyDto(Long deviceId, Long userId, String deviceName, String publicKey, String keyAlgorithm) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.deviceName = deviceName;
        this.publicKey = publicKey;
        this.keyAlgorithm = keyAlgorithm;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
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
}
