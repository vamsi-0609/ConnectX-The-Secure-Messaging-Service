package com.connectx.device.dto;

import jakarta.validation.constraints.NotBlank;

public class RegisterDeviceDto {

    @NotBlank(message = "Device name is required")
    private String deviceName;

    @NotBlank(message = "Public key is required")
    private String publicKey;

    @NotBlank(message = "Key algorithm is required")
    private String keyAlgorithm; // e.g. "ECDH-P256", "RSA-OAEP"

    public RegisterDeviceDto() {}

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
