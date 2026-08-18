import { GroupAddPrivacy, GroupRole, WhoCanEditGroupInfo, WhoCanInvite, WhoCanSendMessages } from '../types';

// Every backend enum value a group screen might need to show gets its human label here, and
// only here -- no component should render a raw backend constant (ADMINS_ONLY, OWNER_ADMIN_ONLY,
// ...) directly.

export const ROLE_LABEL: Record<GroupRole, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
};

export const WHO_CAN_SEND_MESSAGES_OPTIONS: { value: WhoCanSendMessages; label: string; description: string }[] = [
  { value: 'EVERYONE', label: 'Everyone', description: 'All members can send messages' },
  { value: 'ADMINS_ONLY', label: 'Admins only', description: 'Only owners and admins can send messages' },
];

export const WHO_CAN_INVITE_OPTIONS: { value: WhoCanInvite; label: string; description: string }[] = [
  { value: 'ALL_MEMBERS', label: 'Everyone', description: 'Any member can add or invite people' },
  { value: 'OWNER_ADMIN_ONLY', label: 'Owners & admins', description: 'Only owners and admins can add or invite people' },
];

export const WHO_CAN_EDIT_GROUP_INFO_OPTIONS: { value: WhoCanEditGroupInfo; label: string; description: string }[] = [
  { value: 'ALL_MEMBERS', label: 'Everyone', description: 'Any member can edit the group name and photo' },
  { value: 'OWNER_ADMIN_ONLY', label: 'Owners & admins', description: 'Only owners and admins can edit the group name and photo' },
];

// Personal, account-level privacy -- "Who can add me to groups?" (Settings > Privacy > Groups).
// Deliberately a completely separate options list from WHO_CAN_INVITE_OPTIONS above: that one is a
// per-group permission ("who can add members to THIS group"), this one is a global user preference
// ("who can add ME to any group"). Never merge the two, never reuse one list for both.
export const GROUP_ADD_PRIVACY_OPTIONS: { value: GroupAddPrivacy; label: string; description: string }[] = [
  { value: 'ANYONE', label: 'Everyone', description: 'Any ConnectX user can add you to a group' },
  { value: 'CONNECTIONS', label: 'My connections', description: 'Only people you\'re connected with can add you' },
  { value: 'NOBODY', label: 'Nobody', description: 'No one can add you -- they can still invite you' },
];

export function whoCanSendMessagesLabel(value: WhoCanSendMessages): string {
  return WHO_CAN_SEND_MESSAGES_OPTIONS.find((o) => o.value === value)?.label ?? 'Everyone';
}

export function whoCanInviteLabel(value: WhoCanInvite): string {
  return WHO_CAN_INVITE_OPTIONS.find((o) => o.value === value)?.label ?? 'Owners & admins';
}

export function whoCanEditGroupInfoLabel(value: WhoCanEditGroupInfo): string {
  return WHO_CAN_EDIT_GROUP_INFO_OPTIONS.find((o) => o.value === value)?.label ?? 'Owners & admins';
}

// "Direct add" vs. "invitation required" vs. "denied" -- see groupApi.createInvitation /
// CreateGroupInvitationResult. Never expose the outcome string itself.
export function addMemberOutcomeMessage(outcome: 'DIRECT_ADDED' | 'INVITATION_SENT'): string {
  return outcome === 'DIRECT_ADDED' ? 'Added to group' : 'Invitation sent';
}
