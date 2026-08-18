package com.connectx.group.dto;

/**
 * Deliberately carries only the three policy settings themselves -- no groupId, ownerId, actorId,
 * or role. The group id comes from the path, the actor from the authenticated principal, and the
 * actor's authority (docs/CONNECTX_GROUP_ARCHITECTURE.md §6: owner only) is re-derived from the DB
 * by GroupAuthorizationService, never trusted from this request.
 * <p>
 * Every field is optional -- "Request should contain ONLY the settings being changed." A null
 * field is left untouched; only non-null fields are validated and applied, all within one
 * transaction (GroupService#updateSettings), so a request with one invalid field among several
 * valid ones commits none of them.
 */
public class UpdateGroupSettingsRequestDto {

    private String whoCanInvite;
    private String whoCanSendMessages;
    private String whoCanEditGroupInfo;

    public UpdateGroupSettingsRequestDto() {}

    public String getWhoCanInvite() {
        return whoCanInvite;
    }

    public void setWhoCanInvite(String whoCanInvite) {
        this.whoCanInvite = whoCanInvite;
    }

    public String getWhoCanSendMessages() {
        return whoCanSendMessages;
    }

    public void setWhoCanSendMessages(String whoCanSendMessages) {
        this.whoCanSendMessages = whoCanSendMessages;
    }

    public String getWhoCanEditGroupInfo() {
        return whoCanEditGroupInfo;
    }

    public void setWhoCanEditGroupInfo(String whoCanEditGroupInfo) {
        this.whoCanEditGroupInfo = whoCanEditGroupInfo;
    }
}
