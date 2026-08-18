package com.connectx.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Deliberately carries only what a client is allowed to influence -- no creatorUserId, no role,
 * no whoCanInvite. The creator is always the authenticated principal (see GroupController) and is
 * always assigned OWNER (see GroupService#createGroup); there is structurally no field here that
 * could forge either.
 */
public class CreateGroupRequestDto {

    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name must be 100 characters or fewer")
    private String name;

    @Size(max = 500, message = "description must be 500 characters or fewer")
    private String description;

    public CreateGroupRequestDto() {}

    public CreateGroupRequestDto(String name, String description) {
        this.name = name;
        this.description = description;
    }

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
