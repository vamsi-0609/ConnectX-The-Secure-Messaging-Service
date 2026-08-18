import { ApiRequestError } from '../api/apiClient';

// The one place every group-related error code gets translated into plain language. Every group
// component's catch block should go through this instead of surfacing err.message directly --
// the backend's codes/messages are an internal contract (see GroupAuthorizationService,
// GroupService, GroupInvitationService, GroupKeyService), not user-facing copy.
const GROUP_ERROR_MESSAGES: Record<string, string> = {
  GROUP_NOT_FOUND: "This group doesn't exist anymore.",
  NOT_GROUP_MEMBER: "You aren't a member of this group.",
  OWNER_ONLY: 'Only the group owner can do that.',
  ADMIN_OR_OWNER_ONLY: 'Only group admins can do that.',
  CANNOT_MODIFY_OWNER: "The group owner's role can't be changed.",
  CANNOT_REMOVE_OWNER: "The group owner can't be removed.",
  CANNOT_REMOVE_SELF: 'Use "Leave group" to remove yourself.',
  ADMIN_CANNOT_REMOVE_ADMIN: "Admins can't remove other admins.",
  NO_REMOVE_PERMISSION: "You don't have permission to remove members.",
  OWNER_CANNOT_LEAVE: 'Transfer ownership before leaving this group.',
  INVALID_ROLE: 'That role is not available.',
  GROUP_NAME_REQUIRED: 'Enter a group name.',
  GROUP_NAME_TOO_LONG: 'Group name is too long.',
  GROUP_DESCRIPTION_TOO_LONG: 'Description is too long.',
  INVALID_SETTING_VALUE: "Couldn't save that setting.",
  CANNOT_ADD_SELF: "You can't add yourself.",
  NO_INVITE_PERMISSION: "You don't have permission to add members.",
  BLOCKED: "Can't add this person.",
  ALREADY_GROUP_MEMBER: 'Already in the group.',
  GROUP_MEMBER_LIMIT_EXCEEDED: 'This group is full.',
  GROUP_REINVITATION_REQUIRED: 'An invitation is required to re-add this person.',
  TARGET_PRIVACY_NOBODY: "Can't add this person.",
  TARGET_PRIVACY_CONNECTIONS_ONLY: "Can't add this person.",
  USER_NOT_FOUND: "That person couldn't be found.",
  INVITATION_NOT_FOUND: "This invitation doesn't exist anymore.",
  INVITATION_NOT_PENDING: 'This invitation has already been handled.',
  INVITATION_ALREADY_PENDING: 'An invitation is already pending for this person.',
  FORBIDDEN: "You don't have permission to do that.",
  KEY_VERSION_MISMATCH: 'Please update the app and try again.',
  NETWORK_ERROR: "Couldn't reach ConnectX. Check your connection and try again.",
};

export function groupErrorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (err instanceof ApiRequestError) {
    return GROUP_ERROR_MESSAGES[err.code] || fallback;
  }
  if (err instanceof Error && err.message) {
    return fallback;
  }
  return fallback;
}
