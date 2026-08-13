import { Conversation, ConversationPreview } from '../types';

export interface ConversationListMeta {
  previewText: string;
  previewSentAt?: string;
  sortTime: number;
}

function toTimestamp(value?: string): number {
  if (!value) return 0;
  const parsed = new Date(value).getTime();
  return Number.isNaN(parsed) ? 0 : parsed;
}

function isPreviewCurrent(conv: Conversation, preview?: ConversationPreview): boolean {
  if (!preview?.sentAt) {
    return false;
  }

  const previewTime = toTimestamp(preview.sentAt);
  const serverTime = toTimestamp(conv.lastMessageSentAt);

  if (preview.messageId != null && conv.lastMessageId != null) {
    return preview.messageId >= conv.lastMessageId;
  }

  if (serverTime === 0) {
    return true;
  }

  return previewTime >= serverTime;
}

export function getConversationListMeta(
  conv: Conversation,
  preview: ConversationPreview | undefined,
  currentUserId: number
): ConversationListMeta {
  if (preview && isPreviewCurrent(conv, preview) && preview.text) {
    const previewText =
      preview.senderUserId === currentUserId ? `You: ${preview.text}` : preview.text;
    return {
      previewText,
      previewSentAt: preview.sentAt,
      sortTime: toTimestamp(preview.sentAt) || toTimestamp(conv.updatedAt),
    };
  }

  if (conv.lastMessageDeletedForEveryone) {
    return {
      previewText: 'This message was deleted',
      previewSentAt: conv.lastMessageSentAt,
      sortTime: toTimestamp(conv.lastMessageSentAt) || toTimestamp(conv.updatedAt),
    };
  }

  if (conv.lastMessageId) {
    if (conv.lastMessageType === 'IMAGE') {
      return {
        previewText: conv.lastMessageCaption?.trim() ? conv.lastMessageCaption : '📷 Photo',
        previewSentAt: conv.lastMessageSentAt,
        sortTime: toTimestamp(conv.lastMessageSentAt) || toTimestamp(conv.updatedAt),
      };
    }

    if (conv.lastMessageType === 'LOCATION') {
      return {
        previewText: conv.lastMessageCaption?.trim()
          ? `📍 ${conv.lastMessageCaption}`
          : '📍 Shared location',
        previewSentAt: conv.lastMessageSentAt,
        sortTime: toTimestamp(conv.lastMessageSentAt) || toTimestamp(conv.updatedAt),
      };
    }

    if (conv.lastMessageType === 'DOCUMENT') {
      return {
        previewText: conv.lastMessageCaption?.trim()
          ? `📄 ${conv.lastMessageCaption}`
          : '📄 Document',
        previewSentAt: conv.lastMessageSentAt,
        sortTime: toTimestamp(conv.lastMessageSentAt) || toTimestamp(conv.updatedAt),
      };
    }

    return {
      previewText: '🔒 Encrypted message',
      previewSentAt: conv.lastMessageSentAt,
      sortTime: toTimestamp(conv.lastMessageSentAt) || toTimestamp(conv.updatedAt),
    };
  }

  return {
    previewText: 'Start a secure conversation',
    previewSentAt: conv.updatedAt,
    sortTime: toTimestamp(conv.updatedAt),
  };
}

export function shouldReplacePreview(
  existing: ConversationPreview | undefined,
  incoming: ConversationPreview
): boolean {
  if (!existing) {
    return true;
  }

  const existingTime = toTimestamp(existing.sentAt);
  const incomingTime = toTimestamp(incoming.sentAt);

  if (incoming.messageId != null && existing.messageId != null) {
    return incoming.messageId >= existing.messageId;
  }

  return incomingTime >= existingTime;
}

export function reconcilePreview(
  conv: Conversation,
  existing: ConversationPreview | undefined
): ConversationPreview | undefined {
  const serverPreview = previewFromServerConversation(conv);
  if (!serverPreview) {
    return existing;
  }
  if (!existing) {
    return serverPreview;
  }
  if (isPreviewCurrent(conv, existing)) {
    return existing;
  }
  return serverPreview;
}

export function previewFromServerConversation(conv: Conversation): ConversationPreview | null {
  if (!conv.lastMessageSentAt) {
    return null;
  }

  return {
    messageId: conv.lastMessageId,
    text:
      conv.lastMessageDeletedForEveryone
        ? 'This message was deleted'
        : conv.lastMessageType === 'IMAGE'
          ? conv.lastMessageCaption?.trim() || '📷 Photo'
          : conv.lastMessageType === 'LOCATION'
            ? conv.lastMessageCaption?.trim()
              ? `📍 ${conv.lastMessageCaption}`
              : '📍 Shared location'
            : conv.lastMessageType === 'DOCUMENT'
              ? `📄 ${conv.lastMessageCaption?.trim() || 'Document'}`
              : '🔒 Encrypted message',
    sentAt: conv.lastMessageSentAt,
    senderUserId: conv.lastMessageSenderUserId ?? 0,
  };
}
