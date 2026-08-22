package com.connectx.group.dto;

/**
 * Response shape for POST .../keys/request (Phase 7B key reconciliation). Carries only the
 * server-authoritative key version the requester needs and whether a broadcast was actually sent
 * this time (it is throttled -- see GroupKeyService#requestRewrap) -- never key material, never a
 * list of who was asked.
 */
public class GroupKeyRequestResultDto {

    private int keyVersion;
    private boolean broadcastSent;

    public GroupKeyRequestResultDto() {}

    public GroupKeyRequestResultDto(int keyVersion, boolean broadcastSent) {
        this.keyVersion = keyVersion;
        this.broadcastSent = broadcastSent;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public boolean isBroadcastSent() {
        return broadcastSent;
    }

    public void setBroadcastSent(boolean broadcastSent) {
        this.broadcastSent = broadcastSent;
    }
}
