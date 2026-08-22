package com.connectx.group.dto;

/**
 * Response shape for POST .../keys/rotate-for-recovery (Phase 7C). Carries only the newly claimed
 * server-authoritative key version -- never key material. Distinct from
 * {@link GroupKeyRequestResultDto} (which reports a reconciliation request, not a rotation) so the
 * two operations' responses can never be confused with one another.
 */
public class GroupKeyRotationResultDto {

    private int keyVersion;

    public GroupKeyRotationResultDto() {}

    public GroupKeyRotationResultDto(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }
}
