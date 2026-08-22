import { apiRequest, uploadRequest } from './apiClient';
import { ConversationMember, CreateGroupInvitationResult, Group, GroupInvitation, GroupKeyRequestResult, GroupKeyRotationResult, GroupMemberKeyPayload } from '../types';

export const groupApi = {
  createGroup: (name: string, description?: string) =>
    apiRequest<Group>('/groups', {
      method: 'POST',
      body: JSON.stringify({ name, description }),
    }),

  getGroup: (groupId: number) => apiRequest<Group>(`/groups/${groupId}`),

  // Group photo -- deliberately its own endpoint namespace (/groups/{id}/avatar,
  // /group-images/{id}), never the per-user profile-photo API. See utils/groupImage.ts for
  // client-side validation and CreateGroupModal for the upload-after-create flow (a group has no
  // id to upload against until POST /groups has already returned one).
  uploadAvatar: (groupId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return uploadRequest<Group>(`/groups/${groupId}/avatar`, formData);
  },

  removeAvatar: (groupId: number) =>
    apiRequest<Group>(`/groups/${groupId}/avatar`, {
      method: 'DELETE',
    }),

  getGroupMembers: (groupId: number) => apiRequest<ConversationMember[]>(`/groups/${groupId}/members`),

  updateSettings: (
    groupId: number,
    settings: { whoCanInvite?: string; whoCanSendMessages?: string; whoCanEditGroupInfo?: string }
  ) =>
    apiRequest<Group>(`/groups/${groupId}/settings`, {
      method: 'PATCH',
      body: JSON.stringify(settings),
    }),

  // Name/description -- gated by who_can_edit_group_info (OWNER/ADMIN or ALL_MEMBERS depending on
  // the group's own setting), unlike updateSettings above which is always owner-only. A field left
  // out of `info` is left untouched server-side; an explicit empty description clears it.
  updateInfo: (groupId: number, info: { name?: string; description?: string }) =>
    apiRequest<Group>(`/groups/${groupId}/info`, {
      method: 'PATCH',
      body: JSON.stringify(info),
    }),

  changeRole: (groupId: number, userId: number, role: 'ADMIN' | 'MEMBER') =>
    apiRequest<ConversationMember>(`/groups/${groupId}/members/${userId}/role`, {
      method: 'PATCH',
      body: JSON.stringify({ role }),
    }),

  removeMember: (groupId: number, userId: number) =>
    apiRequest<string>(`/groups/${groupId}/members/${userId}`, {
      method: 'DELETE',
    }),

  leaveGroup: (groupId: number) =>
    apiRequest<string>(`/groups/${groupId}/leave`, {
      method: 'POST',
    }),

  transferOwnership: (groupId: number, newOwnerUserId: number) =>
    apiRequest<string>(`/groups/${groupId}/ownership/transfer`, {
      method: 'POST',
      body: JSON.stringify({ newOwnerUserId }),
    }),

  getMyGroupKey: (groupId: number) => apiRequest<GroupMemberKeyPayload>(`/groups/${groupId}/keys/me`),

  submitGroupKey: (
    groupId: number,
    memberUserId: number,
    wrappedKey: string,
    wrapNonce: string,
    keyVersion: number
  ) =>
    apiRequest<GroupMemberKeyPayload>(`/groups/${groupId}/keys`, {
      method: 'POST',
      body: JSON.stringify({ memberUserId, wrappedKey, wrapNonce, keyVersion }),
    }),

  // Phase 7B key reconciliation: "please re-wrap the group's CURRENT key for me" -- never a
  // rotation request. See groupKeyManager.ts's requestReconciliation for the caller-side throttle
  // and crypto/groupKeyManager.ts's module doc for why this exists instead of self-rotating.
  requestGroupKey: (groupId: number) =>
    apiRequest<GroupKeyRequestResult>(`/groups/${groupId}/keys/request`, {
      method: 'POST',
    }),

  // Phase 7C: last-resort recovery rotation -- called ONLY from groupKeyManager's mint fallback
  // after requestGroupKey's reconciliation went unfulfilled within its bounded wait. Claims a
  // genuinely NEW keyVersion (never reuses the current one) so newly-minted key material can never
  // collide with real key material another member already holds for that exact version -- see
  // groupKeyManager.ts's resolveInternal for the corruption bug this replaced.
  rotateGroupKeyForRecovery: (groupId: number) =>
    apiRequest<GroupKeyRotationResult>(`/groups/${groupId}/keys/rotate-for-recovery`, {
      method: 'POST',
    }),

  deleteGroup: (groupId: number) =>
    apiRequest<string>(`/groups/${groupId}`, {
      method: 'DELETE',
    }),

  createInvitation: (groupId: number, targetUserId: number) =>
    apiRequest<CreateGroupInvitationResult>(`/groups/${groupId}/invitations`, {
      method: 'POST',
      body: JSON.stringify({ targetUserId }),
    }),

  acceptInvitation: (invitationId: number) =>
    apiRequest<GroupInvitation>(`/groups/invitations/${invitationId}/accept`, { method: 'POST' }),

  rejectInvitation: (invitationId: number) =>
    apiRequest<GroupInvitation>(`/groups/invitations/${invitationId}/reject`, { method: 'POST' }),

  cancelInvitation: (invitationId: number) =>
    apiRequest<GroupInvitation>(`/groups/invitations/${invitationId}/cancel`, { method: 'POST' }),

  getReceivedInvitations: () => apiRequest<GroupInvitation[]>('/groups/invitations/received'),

  getSentInvitations: () => apiRequest<GroupInvitation[]>('/groups/invitations/sent'),
};
