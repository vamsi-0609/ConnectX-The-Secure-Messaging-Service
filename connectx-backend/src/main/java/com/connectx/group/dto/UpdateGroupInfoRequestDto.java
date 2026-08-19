package com.connectx.group.dto;

/**
 * Editable group "profile" fields -- name and description -- kept deliberately separate from
 * UpdateGroupSettingsRequestDto (the three policy ENUMs), since the two are gated by different
 * authorization rules: this DTO's fields are governed by who_can_edit_group_info
 * (GroupAuthorizationService#requireCanEditGroupInfo, OWNER/ADMIN or ALL_MEMBERS depending on the
 * setting), while the policy ENUMs themselves can only ever be changed by the OWNER
 * (GroupAuthorizationService#requireOwner). No groupId/actorId here for the same reason as that
 * sibling DTO: the group id comes from the path, the actor from the authenticated principal.
 * <p>
 * Both fields are optional -- a null field is left untouched. An explicit empty/blank description
 * clears it (mirrors GroupService#validateDescription); a null or blank name is rejected by
 * GroupService#validateName rather than silently ignored, since "no name" is never a valid group
 * state.
 */
public class UpdateGroupInfoRequestDto {

    private String name;
    private String description;

    public UpdateGroupInfoRequestDto() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
