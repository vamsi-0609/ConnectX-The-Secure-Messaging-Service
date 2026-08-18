import { apiRequest } from './apiClient';
import { ConversationMember, CreateGroupInvitationResult, Group, GroupInvitation, GroupMemberKeyPayload } from '../types';

export const groupApi = {
  createGroup: (name: string, description?: string) =>
    apiRequest<Group>('/groups', {
      method: 'POST',
      body: JSON.stringify({ name, description }),
    }),

  getGroup: (groupId: number) => apiRequest<Group>(`/groups/${groupId}`),

  getGroupMembers: (groupId: number) => apiRequest<ConversationMember[]>(`/groups/${groupId}/members`),

  updateSettings: (
    groupId: number,
    settings: { whoCanInvite?: string; whoCanSendMessages?: string; whoCanEditGroupInfo?: string }
  ) =>
    apiRequest<Group>(`/groups/${groupId}/settings`, {
      method: 'PATCH',
      body: JSON.stringify(settings),
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
