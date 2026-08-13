package com.connectx.user.dto;

public class UserIdentityKeyDto {

    private String masterPublicKey;
    private String masterPrivateKey;

    public UserIdentityKeyDto() {}

    public UserIdentityKeyDto(String masterPublicKey, String masterPrivateKey) {
        this.masterPublicKey = masterPublicKey;
        this.masterPrivateKey = masterPrivateKey;
    }

    public String getMasterPublicKey() {
        return masterPublicKey;
    }

    public void setMasterPublicKey(String masterPublicKey) {
        this.masterPublicKey = masterPublicKey;
    }

    public String getMasterPrivateKey() {
        return masterPrivateKey;
    }

    public void setMasterPrivateKey(String masterPrivateKey) {
        this.masterPrivateKey = masterPrivateKey;
    }
}
