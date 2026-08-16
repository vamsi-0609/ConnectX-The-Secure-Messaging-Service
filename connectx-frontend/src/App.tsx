import React, { useEffect, useState, useCallback, useRef } from 'react';
import { AuthModal } from './features/auth/AuthModal';
import { ChatListSidebar } from './components/layout/ChatListSidebar';
import { ChatScreen } from './components/chat/ChatScreen';

// Lazily loaded: only fetched when the user actually opens one of these panels,
// keeping them out of the initial bundle without changing how/when they appear.
const ContactInfoDrawer = React.lazy(() =>
  import('./components/chat/ContactInfoDrawer').then((m) => ({ default: m.ContactInfoDrawer }))
);
const UserSearchModal = React.lazy(() =>
  import('./components/chat/UserSearchModal').then((m) => ({ default: m.UserSearchModal }))
);
const DeviceManagerModal = React.lazy(() =>
  import('./components/devices/DeviceManagerModal').then((m) => ({ default: m.DeviceManagerModal }))
);
const ProfileModal = React.lazy(() =>
  import('./components/profile/ProfileModal').then((m) => ({ default: m.ProfileModal }))
);
import { useWebSocket } from './websocket/WebSocketContext';
import { wsClient } from './websocket/WebSocketClient';
import { conversationApi } from './api/conversationApi';
import { messageApi } from './api/messageApi';
import { userApi } from './api/userApi';
import { deviceApi } from './api/deviceApi';
import { authApi } from './api/authApi';
import { keyManager } from './crypto/keyManager';
import { decryptMessage } from './crypto/decryption';
import { encryptMessage } from './crypto/encryption';
import { ensureLocalCryptoDevice } from './crypto/deviceSession';
import { conversationCache } from './cache/conversationCache';
import {
  loadCachedConversations,
  saveCachedConversations,
  clearCachedConversationLists,
} from './cache/conversationListCache';
import { applyTheme, isDarkTheme } from './utils/theme';
import { soundManager } from './utils/notificationSound';
import { browserNotifications } from './utils/browserNotifications';
import { appBadge } from './utils/appBadge';
import { registerWebPushSubscription } from './utils/pushSubscription';
import {
  consumePendingShare,
  hasPendingShareMarker,
  splitSharedFiles,
  stripShareTargetParam,
  PendingShare,
} from './utils/shareTarget';
import { NotificationToast, ToastNotificationData } from './components/common/NotificationToast';
import { PWAInstallBanner } from './components/common/PWAInstallBanner';
import { User, Conversation, Message, AuthResponse, ConversationPreview } from './types';
import { MessageSquare, Plus, Share2, X } from 'lucide-react';
import {
  getConversationListMeta,
  previewFromServerConversation,
  reconcilePreview,
  shouldReplacePreview,
} from './utils/conversationList';
import { getLocationPreviewText } from './utils/googleMaps';

function getOtherParticipant(conv: Conversation, currentUserId: number): User | null {
  if (!conv.members?.length) return null;
  const other = conv.members.find((m) => m.user?.id && m.user.id !== currentUserId);
  return other?.user ?? null;
}

function sortMessages(messages: Message[]): Message[] {
  return [...messages].sort(
    (a, b) => new Date(a.sentAt).getTime() - new Date(b.sentAt).getTime()
  );
}

// Monotonic tiebreaker so several optimistic messages created within the same
// millisecond (e.g. sending a batch of images) never collide on `-Date.now()`
// alone, which would otherwise produce duplicate ids/React keys.
let optimisticIdCounter = 0;
function nextOptimisticId(): number {
  optimisticIdCounter = (optimisticIdCounter + 1) % 1000;
  return -(Date.now() * 1000 + optimisticIdCounter);
}

function mergeMessagesForConversation(
  conversationId: number,
  serverMessages: Message[],
  existingMessages: Message[]
): Message[] {
  const msgMap = new Map<number, Message>();

  serverMessages.forEach((m) => msgMap.set(m.id, m));

  existingMessages
    .filter((m) => m.conversationId === conversationId && m.id < 0)
    .forEach((pending) => {
      const hasServerCopy = serverMessages.some((s) => {
        if (pending.messageType === 'IMAGE' && s.messageType === 'IMAGE') {
          return pending.mediaId != null && s.mediaId === pending.mediaId;
        }
        if (pending.messageType === 'LOCATION' && s.messageType === 'LOCATION') {
          return pending.latitude === s.latitude && pending.longitude === s.longitude;
        }
        if (pending.messageType === 'DOCUMENT' && s.messageType === 'DOCUMENT') {
          return pending.mediaId != null && s.mediaId === pending.mediaId;
        }
        return s.ciphertext === pending.ciphertext && s.nonce === pending.nonce;
      });
      if (!hasServerCopy) {
        msgMap.set(pending.id, pending);
      }
    });

  return sortMessages(Array.from(msgMap.values()));
}

function getImagePreviewText(caption?: string): string {
  return caption?.trim() ? caption : '📷 Photo';
}

function getLocationListPreviewText(locationLabel?: string): string {
  return `📍 ${getLocationPreviewText(locationLabel)}`;
}

function previewFromMessage(message: Message, text?: string): ConversationPreview {
  let previewText: string;
  if (message.messageType === 'IMAGE') {
    previewText = getImagePreviewText(text || message.caption);
  } else if (message.messageType === 'LOCATION') {
    previewText = getLocationListPreviewText(message.locationLabel);
  } else if (message.messageType === 'DOCUMENT') {
    previewText = `📄 ${text || message.caption || 'Document'}`;
  } else {
    previewText = text || message.decryptedContent || '🔒 Encrypted message';
  }

  return {
    messageId: message.id > 0 ? message.id : undefined,
    text: previewText,
    sentAt: message.sentAt,
    senderUserId: message.senderUserId,
  };
}

function messageFromWsPayload(payload: Record<string, unknown>): Message {
  return {
    id: payload.messageId as number,
    conversationId: payload.conversationId as number,
    senderUserId: payload.senderUserId as number,
    senderUsername: payload.senderUsername as string,
    senderDeviceId: payload.senderDeviceId as number | undefined,
    recipientDeviceId: payload.recipientDeviceId as number | undefined,
    messageType: (payload.messageType as Message['messageType']) || 'TEXT',
    mediaId: payload.mediaId as number | undefined,
    caption: payload.caption as string | undefined,
    latitude: payload.latitude as number | undefined,
    longitude: payload.longitude as number | undefined,
    locationLabel: payload.locationLabel as string | undefined,
    mimeType: payload.mimeType as string | undefined,
    fileSizeBytes: payload.fileSizeBytes as number | undefined,
    encryptionAlgorithm: payload.encryptionAlgorithm as string | undefined,
    ciphertext: (payload.ciphertext as string) || '',
    nonce: (payload.nonce as string) || '',
    sentAt: payload.sentAt as string,
    deletedForEveryone: false,
    replyToMessageId: payload.replyToMessageId as number | undefined,
    replyToSenderUsername: payload.replyToSenderUsername as string | undefined,
    replyToMessageType: payload.replyToMessageType as Message['messageType'] | undefined,
    replyToCaption: payload.replyToCaption as string | undefined,
    replyToDeleted: payload.replyToDeleted as boolean | undefined,
    reactions: (payload.reactions as Message['reactions']) || [],
    forwarded: payload.forwarded as boolean | undefined,
  };
}

function withoutLastMessagePreview(conv: Conversation): Conversation {
  return {
    ...conv,
    lastMessageId: undefined,
    lastMessageSenderUserId: undefined,
    lastMessageSentAt: undefined,
    lastMessageDeletedForEveryone: false,
  };
}

export const App: React.FC = () => {
  const [currentUser, setCurrentUser] = useState<User | null>(() => {
    const savedUser = localStorage.getItem('connectx_user');
    try {
      return savedUser ? JSON.parse(savedUser) : null;
    } catch {
      return null;
    }
  });
  const [conversations, setConversations] = useState<Conversation[]>(() =>
    currentUser ? loadCachedConversations(currentUser.id) : []
  );
  const [activeConversation, setActiveConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [hasMoreMessages, setHasMoreMessages] = useState<boolean>(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState<boolean>(false);
  const [unreadConversationIds, setUnreadConversationIds] = useState<Set<number>>(new Set());
  const [conversationPreviews, setConversationPreviews] = useState<Record<number, ConversationPreview>>({});
  // conversationId -> the other participant's username, while they're actively typing.
  // A local safety-net timer clears an entry if a stop event is ever dropped.
  const [typingByConversation, setTypingByConversation] = useState<Record<number, string>>({});
  const typingTimersRef = useRef<Record<number, ReturnType<typeof setTimeout>>>({});
  const [pinnedMessage, setPinnedMessage] = useState<Message | null>(null);
  const pinnedMessageRequestSeqRef = useRef<number>(0);

  const [isDarkMode, setIsDarkMode] = useState<boolean>(() => isDarkTheme());
  const [showRawCiphertext, setShowRawCiphertext] = useState(false);
  const [showInfoDrawer, setShowInfoDrawer] = useState(false);

  const [showSearchModal, setShowSearchModal] = useState(false);
  const [showDeviceModal, setShowDeviceModal] = useState(false);
  const [showProfileModal, setShowProfileModal] = useState(false);

  // Files handed off from the OS "Share" sheet via the PWA share_target (see
  // public/sw.js), waiting on the user to pick a conversation. Cleared as soon
  // as ChatScreen/MessageInput consumes it into the normal media-send flow.
  const [pendingShare, setPendingShare] = useState<PendingShare | null>(null);

  const { subscribe, reconnect, status } = useWebSocket();

  const activeConversationRef = useRef<Conversation | null>(null);
  const activeConversationIdRef = useRef<number | null>(null);
  const activeRequestSeqRef = useRef<number>(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const conversationsLoadSeqRef = useRef(0);
  // pinnedAt lets `loadConversations` below evict an entry that's stopped coming
  // back from the server for reasons other than an explicit delete/restore event,
  // instead of unioning it back into the list forever.
  const pinnedConversationsRef = useRef<Map<number, { conv: Conversation; pinnedAt: number }>>(new Map());
  const PINNED_CONVERSATION_MAX_AGE_MS = 5 * 60 * 1000;

  useEffect(() => {
    activeConversationRef.current = activeConversation;
    activeConversationIdRef.current = activeConversation?.id ?? null;

    if (activeConversation) {
      wsClient.setActiveConversation(activeConversation.id);
      setUnreadConversationIds((prev) => {
        const next = new Set(prev);
        next.delete(activeConversation.id);
        return next;
      });
      // Opening a conversation always clears a manual "mark unread" override — the
      // WS read receipt below clears it server-side too, this just avoids waiting
      // on a round trip for the sidebar to stop showing the unread dot.
      setConversations((prev) =>
        prev.map((c) =>
          c.id === activeConversation.id && (c.manuallyMarkedUnread || c.members?.some((m) => m.manuallyMarkedUnread))
            ? {
                ...c,
                manuallyMarkedUnread: false,
                members: c.members?.map((m) => (m.manuallyMarkedUnread ? { ...m, manuallyMarkedUnread: false } : m)),
              }
            : c
        )
      );
      wsClient.sendRead(activeConversation.id);
      setPinnedMessage(null);
    } else {
      wsClient.setActiveConversation(null);
      setPinnedMessage(null);
    }
  }, [activeConversation]);

  const conversationsRef = useRef<Conversation[]>([]);
  const conversationPreviewsRef = useRef<Record<number, ConversationPreview>>({});
  useEffect(() => {
    conversationsRef.current = conversations;
  }, [conversations]);

  useEffect(() => {
    conversationPreviewsRef.current = conversationPreviews;
  }, [conversationPreviews]);

  const [currentToast, setCurrentToast] = useState<ToastNotificationData | null>(null);
  const [notificationsEnabled, setNotificationsEnabled] = useState(() => soundManager.isEnabled());

  const handleToggleNotifications = () => {
    const soundNext = soundManager.toggle();
    browserNotifications.setEnabled(soundNext);
    setNotificationsEnabled(soundNext);
  };

  const handleSelectToastConversation = (convId: number) => {
    setCurrentToast(null);
    const target = conversationsRef.current.find((c) => c.id === convId);
    if (target) {
      setActiveConversation(target);
      setUnreadConversationIds((prev) => {
        const next = new Set(prev);
        next.delete(convId);
        return next;
      });
    }
  };

  useEffect(() => {
    const count = unreadConversationIds.size;
    if (count > 0) {
      document.title = `(${count}) ConnectX - Secure Messaging`;
    } else {
      document.title = 'ConnectX - Secure Messaging';
    }
    appBadge.set(count);
  }, [unreadConversationIds]);

  useEffect(() => {
    if (currentUser) {
      browserNotifications.requestPermission().then((granted) => {
        if (granted) {
          registerWebPushSubscription();
        }
      }).catch(() => {});
    }
  }, [currentUser]);

  useEffect(() => {
    applyTheme(isDarkMode);
  }, [isDarkMode]);

  // One-time: pick up any files the OS "Share" sheet handed off via the PWA
  // share_target (see public/sw.js + utils/shareTarget.ts). The marker query
  // param is stripped immediately so a refresh doesn't re-trigger this.
  useEffect(() => {
    if (!hasPendingShareMarker()) return;
    stripShareTargetParam();
    consumePendingShare()
      .then((share) => {
        if (share) setPendingShare(share);
      })
      .catch((err) => console.warn('[ConnectX] Failed to load shared files:', err));
  }, []);

  useEffect(() => {
    const handleAuthExpired = () => {
      console.warn('[ConnectX] Authentication session expired. Resetting session state.');
      wsClient.disconnect();
      Object.values(typingTimersRef.current).forEach(clearTimeout);
      typingTimersRef.current = {};
      processedMessageIdsRef.current.clear();
      conversationCache.clearAll();
      clearCachedConversationLists();
      appBadge.clear();
      // Mirror handleLogout's cleanup: an expired session shouldn't leave stale
      // credentials in localStorage (a reload would otherwise briefly re-hydrate
      // the previous account), nor leak its unread/typing state into whichever
      // account logs in next on this tab.
      localStorage.removeItem('connectx_token');
      localStorage.removeItem('connectx_refresh_token');
      localStorage.removeItem('connectx_user');
      setUnreadConversationIds(new Set());
      setTypingByConversation({});
      setPendingShare(null);
      setCurrentUser(null);
      setConversations([]);
      pinnedConversationsRef.current.clear();
      setActiveConversation(null);
      setMessages([]);
      setConversationPreviews({});
    };

    window.addEventListener('connectx_auth_expired', handleAuthExpired);

    const token = localStorage.getItem('connectx_token');
    const refreshToken = localStorage.getItem('connectx_refresh_token');
    if ((token || refreshToken) && currentUser) {
      userApi.getCurrentUser()
        .then((freshUser) => {
          setCurrentUser(freshUser);
          localStorage.setItem('connectx_user', JSON.stringify(freshUser));
          return ensureLocalCryptoDevice(freshUser);
        })
        .then(() => reconnect())
        .catch((err) => {
          console.warn('[ConnectX Auth] Session validation encountered temporary error:', err);
        });
    }

    return () => {
      window.removeEventListener('connectx_auth_expired', handleAuthExpired);
    };
  }, []);

  const handleAuthSuccess = (response: AuthResponse) => {
    localStorage.setItem('connectx_token', response.accessToken);
    if (response.refreshToken) {
      localStorage.setItem('connectx_refresh_token', response.refreshToken);
    }
    localStorage.setItem('connectx_user', JSON.stringify(response.user));
    setCurrentUser(response.user);

    ensureLocalCryptoDevice(response.user)
      .then(() => reconnect())
      .catch((err) => console.error('[ConnectX E2EE] Crypto Device initialization failure:', err));
  };

  const handleLogout = async () => {
    wsClient.disconnect();
    Object.values(typingTimersRef.current).forEach(clearTimeout);
    typingTimersRef.current = {};
    processedMessageIdsRef.current.clear();
    try {
      await authApi.logout();
    } catch {
      // Session may already be cleared locally.
    }
    localStorage.removeItem('connectx_token');
    localStorage.removeItem('connectx_refresh_token');
    localStorage.removeItem('connectx_user');
    conversationCache.clearAll();
    clearCachedConversationLists();
    appBadge.clear();
    // The app never unmounts across a logout→login cycle in the same tab, so
    // these must be reset explicitly or the next account inherits the previous
    // one's stale unread badge count and typing indicators.
    setUnreadConversationIds(new Set());
    setTypingByConversation({});
    setPendingShare(null);
    setCurrentUser(null);
    setConversations([]);
    pinnedConversationsRef.current.clear();
    setActiveConversation(null);
    setMessages([]);
    setConversationPreviews({});
    setShowProfileModal(false);
  };

  const loadConversations = useCallback(async () => {
    if (!currentUser) return;
    const requestSeq = ++conversationsLoadSeqRef.current;
    try {
      const data = await conversationApi.getConversations();
      if (requestSeq !== conversationsLoadSeqRef.current) {
        return;
      }

      const syncedPreviews: Record<number, ConversationPreview> = {
        ...conversationPreviewsRef.current,
      };
      data.forEach((conv) => {
        const reconciled = reconcilePreview(conv, syncedPreviews[conv.id]);
        if (reconciled) {
          syncedPreviews[conv.id] = reconciled;
        } else if (!syncedPreviews[conv.id]) {
          const serverPreview = previewFromServerConversation(conv);
          if (serverPreview) {
            syncedPreviews[conv.id] = serverPreview;
          }
        }
      });
      setConversationPreviews(syncedPreviews);

      setConversations(() => {
        const byId = new Map<number, Conversation>();

        data.forEach((conv) => {
          byId.set(conv.id, conv);
          pinnedConversationsRef.current.delete(conv.id);
        });

        const pinnedCutoff = Date.now() - PINNED_CONVERSATION_MAX_AGE_MS;
        pinnedConversationsRef.current.forEach(({ conv, pinnedAt }, id) => {
          if (pinnedAt < pinnedCutoff) {
            // Stopped coming back from the server a while ago for some reason other
            // than an explicit delete/restore event — stop resurrecting it forever.
            pinnedConversationsRef.current.delete(id);
            return;
          }
          if (!byId.has(id)) {
            byId.set(id, conv);
          }
        });

        const activeConv = activeConversationRef.current;
        if (activeConv) {
          const existing = byId.get(activeConv.id);
          byId.set(activeConv.id, {
            ...(existing ?? activeConv),
            ...activeConv,
            members: activeConv.members?.length ? activeConv.members : existing?.members ?? activeConv.members,
          });
        }

        return Array.from(byId.values()).sort((a, b) => {
          const aMeta = getConversationListMeta(a, syncedPreviews[a.id], currentUser!.id);
          const bMeta = getConversationListMeta(b, syncedPreviews[b.id], currentUser!.id);
          return bMeta.sortTime - aMeta.sortTime;
        });
      });
    } catch (err) {
      console.warn('[ConnectX] Failed to load conversations from network (preserving cached state):', err);
    }
  }, [currentUser]);

  const upsertConversation = useCallback((conv: Conversation) => {
    pinnedConversationsRef.current.set(conv.id, { conv, pinnedAt: Date.now() });
    setConversations((prev) => {
      const existingIndex = prev.findIndex((c) => c.id === conv.id);
      if (existingIndex >= 0) {
        const next = [...prev];
        next[existingIndex] = {
          ...next[existingIndex],
          ...conv,
          members: conv.members?.length ? conv.members : next[existingIndex].members,
        };
        return next;
      }
      return [conv, ...prev];
    });
  }, []);

  useEffect(() => {
    if (currentUser) {
      loadConversations();
    }
  }, [currentUser, loadConversations]);

  // Mirror the conversation list to localStorage (debounced) so a reload can
  // paint instantly from cache next time instead of showing a blank sidebar
  // while the network request is in flight. Only non-sensitive metadata is
  // written — see cache/conversationListCache.ts.
  const conversationCacheWriteTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    const userId = currentUser?.id;
    if (!userId) return;
    if (conversationCacheWriteTimerRef.current) {
      clearTimeout(conversationCacheWriteTimerRef.current);
    }
    conversationCacheWriteTimerRef.current = setTimeout(() => {
      saveCachedConversations(userId, conversations);
    }, 400);
    return () => {
      if (conversationCacheWriteTimerRef.current) {
        clearTimeout(conversationCacheWriteTimerRef.current);
      }
    };
  }, [conversations, currentUser?.id]);

  const prevWsStatusRef = useRef<string>('');

  // ── P0-5: Mobile back-button navigation ──────────────────────────────────
  // Push a history entry when the user navigates to a sub-screen so the
  // Android/iOS back button navigates within the app instead of exiting the PWA.
  const isHandlingPopRef = useRef(false);

  useEffect(() => {
    const isSubScreen =
      activeConversation !== null || showProfileModal || showSearchModal || showDeviceModal;

    if (isSubScreen) {
      // Push a synthetic history entry so there is something to pop back to
      if (window.history.state?.connectxNav !== true) {
        window.history.pushState({ connectxNav: true }, '');
      }
    }
  }, [activeConversation, showProfileModal, showSearchModal, showDeviceModal]);

  useEffect(() => {
    const handlePopState = () => {
      if (isHandlingPopRef.current) return;
      isHandlingPopRef.current = true;

      // Close the topmost screen in priority order
      if (showDeviceModal) {
        setShowDeviceModal(false);
      } else if (showSearchModal) {
        setShowSearchModal(false);
      } else if (showProfileModal) {
        setShowProfileModal(false);
      } else if (activeConversation) {
        setActiveConversation(null);
      }
      // Re-push so a second back still works if multiple layers are open
      const stillSubScreen =
        showDeviceModal || showSearchModal || showProfileModal || activeConversation !== null;
      if (stillSubScreen) {
        window.history.pushState({ connectxNav: true }, '');
      }

      requestAnimationFrame(() => { isHandlingPopRef.current = false; });
    };

    window.addEventListener('popstate', handlePopState);
    return () => window.removeEventListener('popstate', handlePopState);
  }, [activeConversation, showProfileModal, showSearchModal, showDeviceModal]);

  const decryptSingleMessage = useCallback(
    async (msg: Message, userId: number, peerUserId?: number | null): Promise<Message> => {
      if (msg.messageType === 'IMAGE' || msg.messageType === 'LOCATION') {
        return { ...msg, decryptionError: false };
      }

      if (msg.decryptedContent) {
        return msg;
      }

      const cached = await keyManager.getDecryptedMessage(msg.id);
      if (cached) {
        return { ...msg, decryptedContent: cached, decryptionError: false };
      }

      let resolvedPeerUserId: number | null = peerUserId ?? null;
      try {
        const myPrivateKey = await keyManager.getPrivateKey(userId);
        if (!myPrivateKey) {
          return { ...msg, decryptionError: true };
        }

        if (!resolvedPeerUserId) {
          resolvedPeerUserId =
            msg.senderUserId === userId
              ? activeConversationRef.current
                ? getOtherParticipant(activeConversationRef.current, userId)?.id ?? null
                : null
              : msg.senderUserId;
        }

        if (!resolvedPeerUserId) {
          return { ...msg, decryptionError: true };
        }

        let userKeys = conversationCache.getPublicKeys(resolvedPeerUserId);
        if (!userKeys || userKeys.length === 0) {
          userKeys = await deviceApi.getUserPublicKeys(resolvedPeerUserId);
          if (userKeys && userKeys.length > 0) {
            conversationCache.setPublicKeys(resolvedPeerUserId, userKeys);
          }
        }

        if (!userKeys || userKeys.length === 0) {
          return { ...msg, decryptionError: true };
        }

        const decrypted = await decryptMessage(
          myPrivateKey,
          userKeys[0].publicKey,
          msg.ciphertext,
          msg.nonce
        );
        await keyManager.saveDecryptedMessage(msg.id, decrypted);
        return { ...msg, decryptedContent: decrypted, decryptionError: false };
      } catch (err) {
        console.warn(`[ConnectX E2EE] Message ${msg.id} decryption failed:`, err);
        // A cached-but-stale public key (e.g. the peer rotated/added a device) is
        // a likely cause of an otherwise-unexplained failure — drop it so the next
        // attempt fetches a fresh key instead of reusing the same bad one for up
        // to KEY_CACHE_TTL_MS longer.
        if (resolvedPeerUserId) {
          conversationCache.invalidatePublicKeys(resolvedPeerUserId);
        }
        return { ...msg, decryptionError: true };
      }
    },
    []
  );

  // Edited ciphertext must bypass both the in-memory `decryptedContent` field and the
  // IndexedDB decrypted-message cache (both would otherwise serve stale pre-edit plaintext),
  // so this deliberately does not reuse decryptSingleMessage's cache-first shortcuts.
  const reDecryptEditedMessage = useCallback(
    async (ciphertext: string, nonce: string, userId: number, peerUserId?: number | null): Promise<{ decryptedContent?: string; decryptionError: boolean }> => {
      try {
        const myPrivateKey = await keyManager.getPrivateKey(userId);
        if (!myPrivateKey || !peerUserId) {
          return { decryptionError: true };
        }
        let userKeys = conversationCache.getPublicKeys(peerUserId);
        if (!userKeys || userKeys.length === 0) {
          userKeys = await deviceApi.getUserPublicKeys(peerUserId);
          if (userKeys && userKeys.length > 0) {
            conversationCache.setPublicKeys(peerUserId, userKeys);
          }
        }
        if (!userKeys || userKeys.length === 0) {
          return { decryptionError: true };
        }
        const decrypted = await decryptMessage(myPrivateKey, userKeys[0].publicKey, ciphertext, nonce);
        return { decryptedContent: decrypted, decryptionError: false };
      } catch (err) {
        console.warn('[ConnectX E2EE] Re-decryption after edit failed:', err);
        if (peerUserId) {
          conversationCache.invalidatePublicKeys(peerUserId);
        }
        return { decryptionError: true };
      }
    },
    []
  );

  const refreshPinnedMessage = useCallback(
    async (conversationId: number) => {
      const requestSeq = ++pinnedMessageRequestSeqRef.current;
      try {
        const pinned = await messageApi.getPinnedMessage(conversationId);
        if (requestSeq !== pinnedMessageRequestSeqRef.current || activeConversationIdRef.current !== conversationId) {
          return;
        }
        if (!pinned) {
          setPinnedMessage(null);
          return;
        }
        const activeConv = activeConversationRef.current;
        const peerUserId = activeConv ? getOtherParticipant(activeConv, currentUser!.id)?.id : undefined;
        const processed =
          pinned.messageType === 'IMAGE' || pinned.messageType === 'LOCATION' || pinned.messageType === 'DOCUMENT'
            ? pinned
            : await decryptSingleMessage(pinned, currentUser!.id, peerUserId);
        if (requestSeq === pinnedMessageRequestSeqRef.current && activeConversationIdRef.current === conversationId) {
          setPinnedMessage(processed);
        }
      } catch (err) {
        console.warn('[ConnectX] Failed to fetch pinned message:', err);
      }
    },
    [currentUser, decryptSingleMessage]
  );

  useEffect(() => {
    if (activeConversation) {
      refreshPinnedMessage(activeConversation.id);
    }
  }, [activeConversation, refreshPinnedMessage]);

  const updatePreviewIfNewer = useCallback((conversationId: number, preview: ConversationPreview) => {
    setConversationPreviews((prev) => {
      const existing = prev[conversationId];
      if (!shouldReplacePreview(existing, preview)) {
        return prev;
      }
      return { ...prev, [conversationId]: preview };
    });
  }, []);

  const fetchAndSetMessagesForConversation = useCallback(
    async (convId: number, targetSeq: number, signal?: AbortSignal) => {
      if (!currentUser) return;
      try {
        const response = await messageApi.getMessages(convId, { limit: 30 }, signal);
        if (targetSeq !== activeRequestSeqRef.current || activeConversationIdRef.current !== convId) {
          return;
        }

        const activeConv =
          conversationsRef.current.find((c) => c.id === convId) ?? activeConversationRef.current;
        const peerUserId = activeConv ? getOtherParticipant(activeConv, currentUser.id)?.id : undefined;

        const decryptedList = await Promise.all(
          response.messages.map((m) => decryptSingleMessage(m, currentUser.id, peerUserId))
        );

        if (targetSeq !== activeRequestSeqRef.current || activeConversationIdRef.current !== convId) {
          return;
        }

        // Merge against whatever's already cached (rather than replacing outright)
        // so a still-pending optimistic send isn't wiped from view just because the
        // server's window doesn't include it yet — e.g. a fast switch-away-and-back
        // triggering this fetch while the user's own message hasn't been ACKed.
        const existingMessages = conversationCache.getConversation(convId)?.messages ?? [];
        const sorted = mergeMessagesForConversation(convId, decryptedList, existingMessages);
        conversationCache.setConversation(convId, {
          messages: sorted,
          hasMore: response.hasMore,
          oldestCursor: response.nextCursor,
        });

        setMessages(sorted);
        setHasMoreMessages(response.hasMore);

        if (sorted.length > 0) {
          const last = sorted[sorted.length - 1];
          updatePreviewIfNewer(convId, previewFromMessage(last));
        }
      } catch (err: unknown) {
        if ((err as Error)?.name === 'AbortError') {
          return;
        }
        console.warn(`[ConnectX] Failed to load messages for conversation ${convId} from network:`, err);
        // Fallback: If network failed, check if we have cached messages in LRU cache so we don't display empty chat
        const cached = conversationCache.getConversation(convId);
        if (cached && cached.messages.length > 0) {
          if (targetSeq === activeRequestSeqRef.current && activeConversationIdRef.current === convId) {
            setMessages(cached.messages);
            setHasMoreMessages(cached.hasMore);
          }
        }
      }
    },
    [currentUser, decryptSingleMessage, updatePreviewIfNewer]
  );

  // ── P0-1: Reload conversations when WebSocket reconnects ──────────────────
  // If the WS was dropped and reconnected, fetch fresh conversations to catch
  // any messages that arrived while the socket was disconnected.
  useEffect(() => {
    const prev = prevWsStatusRef.current;
    prevWsStatusRef.current = status;
    // Only reload on a genuine reconnect (DISCONNECTED → CONNECTED)
    if (prev === 'DISCONNECTED' && status === 'CONNECTED' && currentUser) {
      console.log('[ConnectX] WebSocket reconnected — reloading conversations to catch missed messages.');
      loadConversations();

      // loadConversations() above only refreshes sidebar/list metadata -- it never
      // refetches the currently-open conversation's own message list, so anything that
      // happened there while disconnected (new messages, edits, reactions, read
      // receipts) would otherwise stay stale until the user manually switches away and
      // back. Re-fetch it the same way handleSelectConversation does.
      const activeConvId = activeConversationIdRef.current;
      if (activeConvId) {
        if (abortControllerRef.current) {
          abortControllerRef.current.abort();
        }
        const controller = new AbortController();
        abortControllerRef.current = controller;
        fetchAndSetMessagesForConversation(activeConvId, ++activeRequestSeqRef.current, controller.signal);
      }
    }
  }, [status, currentUser, loadConversations, fetchAndSetMessagesForConversation]);

  const loadOlderMessages = useCallback(async () => {
    const convId = activeConversationIdRef.current;
    if (!convId || !currentUser || isLoadingOlder || !hasMoreMessages) return;

    const cached = conversationCache.getConversation(convId);
    const oldestCursor = cached?.oldestCursor ?? (messages.length > 0 ? messages[0].id : null);
    if (!oldestCursor) return;

    setIsLoadingOlder(true);
    try {
      const response = await messageApi.getMessages(convId, { before: oldestCursor, limit: 30 });
      if (activeConversationIdRef.current !== convId) return;

      const activeConv =
        conversationsRef.current.find((c) => c.id === convId) ?? activeConversationRef.current;
      const peerUserId = activeConv ? getOtherParticipant(activeConv, currentUser.id)?.id : undefined;

      const decryptedOlder = await Promise.all(
        response.messages.map((m) => decryptSingleMessage(m, currentUser.id, peerUserId))
      );
      if (activeConversationIdRef.current !== convId) return;

      setMessages((prev) => {
        const existingIds = new Set(prev.map((m) => m.id));
        const filteredNew = decryptedOlder.filter((m) => !existingIds.has(m.id));
        const merged = sortMessages([...filteredNew, ...prev]);

        conversationCache.setConversation(convId, {
          messages: merged,
          hasMore: response.hasMore,
          oldestCursor: response.nextCursor,
        });

        return merged;
      });

      setHasMoreMessages(response.hasMore);
    } catch (err) {
      console.error('[ConnectX] Failed to load older messages:', err);
    } finally {
      setIsLoadingOlder(false);
    }
  }, [currentUser, isLoadingOlder, hasMoreMessages, messages, decryptSingleMessage]);

  useEffect(() => {
    if (!activeConversation) {
      setMessages([]);
      setHasMoreMessages(false);
    }
  }, [activeConversation]);

  const processedMessageIdsRef = useRef<Set<number>>(new Set());

  useEffect(() => {
    if (!currentUser) return;

    const processedMessageIds = processedMessageIdsRef.current;

    const unsubscribe = subscribe(async (event) => {
      if (event.type === 'MESSAGE_RECEIVED') {
        const payload = event.payload;
        const msgId = payload.messageId as number;
        const conversationId = payload.conversationId as number;

        if (processedMessageIds.has(msgId)) return;
        processedMessageIds.add(msgId);
        // Bounded dedup window -- a session left open for days shouldn't accumulate
        // every message id it's ever seen. 2000 is far beyond any realistic
        // duplicate-redelivery race (WS reconnect, etc.) this set exists to catch.
        if (processedMessageIds.size > 2000) {
          const oldest = processedMessageIds.values().next().value;
          if (oldest !== undefined) {
            processedMessageIds.delete(oldest);
          }
        }

        const currentActive = activeConversationRef.current;

        if (currentActive && conversationId === currentActive.id) {
          if (payload.senderUserId !== currentUser.id) {
            // 1. Mark DELIVERED promptly upon network arrival
            wsClient.sendDelivered(msgId);

            // 2. Decrypt & process message
            const newMsg = messageFromWsPayload(payload as Record<string, unknown>);
            const peerUserId = getOtherParticipant(currentActive, currentUser.id)?.id;
            const processedMsg =
              newMsg.messageType === 'IMAGE' || newMsg.messageType === 'LOCATION' || newMsg.messageType === 'DOCUMENT'
                ? newMsg
                : await decryptSingleMessage(newMsg, currentUser.id, peerUserId);

            // The user may have switched to a different conversation while this was
            // decrypting — re-check the LIVE ref (not the `currentActive` snapshot from
            // before the await) so a slow decrypt can never clobber whichever
            // conversation is actually on screen by the time we get here.
            const stillActive = activeConversationRef.current?.id === conversationId;

            if (stillActive) {
              // 3. Mount message into active chat state
              setMessages((prev) => {
                if (prev.some((m) => m.id === processedMsg.id)) return prev;
                const next = sortMessages([
                  ...prev.filter((m) => m.conversationId === conversationId),
                  processedMsg,
                ]);
                conversationCache.setConversation(conversationId, {
                  messages: next,
                  hasMore: conversationCache.getConversation(conversationId)?.hasMore ?? false,
                  oldestCursor: conversationCache.getConversation(conversationId)?.oldestCursor ?? null,
                });
                return next;
              });

              const preview = previewFromMessage(processedMsg);
              updatePreviewIfNewer(conversationId, preview);

              // 4. Mark READ now that message has actually become visible in active conversation
              wsClient.sendRead(conversationId, msgId);

              const isMuted =
                currentActive.isMuted ||
                (currentActive.mutedUntil && new Date(currentActive.mutedUntil).getTime() > Date.now());

              if (!isMuted) {
                soundManager.playIncomingMessageSound();
              }
            } else {
              // No longer the active conversation — merge into its cache entry only
              // (never touch `messages`, which belongs to whichever conversation is
              // now on screen), and mark it unread like any other background message.
              const cachedConv = conversationCache.getConversation(conversationId);
              if (cachedConv && !cachedConv.messages.some((m) => m.id === processedMsg.id)) {
                conversationCache.setConversation(conversationId, {
                  messages: sortMessages([...cachedConv.messages, processedMsg]),
                  hasMore: cachedConv.hasMore,
                  oldestCursor: cachedConv.oldestCursor,
                });
              }
              updatePreviewIfNewer(conversationId, previewFromMessage(processedMsg));
              setUnreadConversationIds((prev) => new Set(prev).add(conversationId));
            }
          } else {
            // Reconcile our own message that was sent optimistically. If there's no
            // matching optimistic entry (e.g. a forwarded message, which is sent
            // without one), treat it as new and append it instead of silently
            // dropping it from the currently-open conversation.
            let matched = false;
            setMessages((prev) => {
              const updated = prev.map((m) => {
                if (
                  m.id < 0 &&
                  ((payload.clientTempId && m.clientTempId === payload.clientTempId) ||
                    (payload.mediaId && m.mediaId === payload.mediaId) ||
                    (m.ciphertext && m.ciphertext === payload.ciphertext))
                ) {
                  matched = true;
                  return {
                    ...m,
                    id: msgId,
                    sentAt: (payload.sentAt as string) || m.sentAt,
                    status: 'SENT' as const,
                  };
                }
                return m;
              });
              conversationCache.setConversation(conversationId, {
                messages: updated,
                hasMore: conversationCache.getConversation(conversationId)?.hasMore ?? false,
                oldestCursor: conversationCache.getConversation(conversationId)?.oldestCursor ?? null,
              });
              return updated;
            });

            if (!matched) {
              const newMsg = messageFromWsPayload(payload as Record<string, unknown>);
              const peerUserId = getOtherParticipant(currentActive, currentUser.id)?.id;
              const processedMsg =
                newMsg.messageType === 'IMAGE' || newMsg.messageType === 'LOCATION' || newMsg.messageType === 'DOCUMENT'
                  ? newMsg
                  : await decryptSingleMessage(newMsg, currentUser.id, peerUserId);

              // Same re-check as the peer-message branch above: don't let a slow
              // decrypt apply this update to whichever conversation is now active.
              const stillActive = activeConversationRef.current?.id === conversationId;

              if (stillActive) {
                setMessages((prev) => {
                  if (prev.some((m) => m.id === processedMsg.id)) return prev;
                  const next = sortMessages([...prev, processedMsg]);
                  conversationCache.setConversation(conversationId, {
                    messages: next,
                    hasMore: conversationCache.getConversation(conversationId)?.hasMore ?? false,
                    oldestCursor: conversationCache.getConversation(conversationId)?.oldestCursor ?? null,
                  });
                  return next;
                });
              } else {
                const cachedConv = conversationCache.getConversation(conversationId);
                if (cachedConv && !cachedConv.messages.some((m) => m.id === processedMsg.id)) {
                  conversationCache.setConversation(conversationId, {
                    messages: sortMessages([...cachedConv.messages, processedMsg]),
                    hasMore: cachedConv.hasMore,
                    oldestCursor: cachedConv.oldestCursor,
                  });
                }
              }

              const preview = previewFromMessage(processedMsg);
              updatePreviewIfNewer(conversationId, preview);
            }
          }
        } else {
          // Background conversation event
          const conv = conversationsRef.current.find((c) => c.id === conversationId);
          const peerUserId =
            conv && currentUser
              ? getOtherParticipant(conv, currentUser.id)?.id ?? payload.senderUserId
              : payload.senderUserId;

          const backgroundMsg = messageFromWsPayload(payload as Record<string, unknown>);
          const processedMsg =
            backgroundMsg.messageType === 'IMAGE' ||
            backgroundMsg.messageType === 'LOCATION' ||
            backgroundMsg.messageType === 'DOCUMENT'
              ? backgroundMsg
              : await decryptSingleMessage(backgroundMsg, currentUser.id, peerUserId);

          const preview = previewFromMessage(processedMsg);
          updatePreviewIfNewer(conversationId, preview);

          // Update LRU cache for this background conversation if it exists in cache
          const cachedConv = conversationCache.getConversation(conversationId);
          if (cachedConv) {
            if (!cachedConv.messages.some((m) => m.id === processedMsg.id)) {
              const updatedMessages = sortMessages([...cachedConv.messages, processedMsg]);
              conversationCache.setConversation(conversationId, {
                messages: updatedMessages,
                hasMore: cachedConv.hasMore,
                oldestCursor: cachedConv.oldestCursor,
              });
            }
          }

          if (payload.senderUserId !== currentUser.id) {
            setUnreadConversationIds((prev) => new Set(prev).add(conversationId));
            // Mark DELIVERED promptly for background conversation
            wsClient.sendDelivered(msgId);

            const isMuted =
              conv?.isMuted ||
              (conv?.mutedUntil && new Date(conv.mutedUntil).getTime() > Date.now());

            if (!isMuted) {
              soundManager.playIncomingMessageSound();

              const senderName = (payload.senderUsername as string) || 'ConnectX User';
              const senderAvatar = conv ? getOtherParticipant(conv, currentUser.id)?.profileImageUrl : undefined;

              setCurrentToast({
                id: `toast-${msgId}-${Date.now()}`,
                senderName,
                senderAvatar,
                messageText: preview.text,
                conversationId,
                timestamp: (payload.sentAt as string) || new Date().toISOString(),
              });

              browserNotifications.showNotification(`New message from ${senderName}`, {
                body: preview.text,
                conversationId,
                onClick: () => handleSelectToastConversation(conversationId),
              });
            }
          }

          if (conv) {
            upsertConversation({
              ...conv,
              lastMessageId: msgId,
              lastMessageSentAt: payload.sentAt as string,
              lastMessageSenderUserId: payload.senderUserId as number,
              lastMessageType: (payload.messageType as Conversation['lastMessageType']) || 'TEXT',
              lastMessageCaption:
                payload.messageType === 'LOCATION'
                  ? (payload.locationLabel as string | undefined)
                  : (payload.caption as string | undefined),
              updatedAt: payload.sentAt as string,
            });
          }
        }
      } else if (event.type === 'MESSAGE_REACTION_UPDATE') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number;
        const reactions = payload.reactions as Message['reactions'];

        setMessages((prev) => {
          const updated = prev.map((msg) => {
            if (msg.id === msgId) {
              return { ...msg, reactions };
            }
            return msg;
          });
          const activeConv = activeConversationRef.current;
          if (activeConv) {
            conversationCache.setConversation(activeConv.id, {
              messages: updated,
              hasMore: conversationCache.getConversation(activeConv.id)?.hasMore ?? false,
              oldestCursor: conversationCache.getConversation(activeConv.id)?.oldestCursor ?? null,
            });
          }
          return updated;
        });
      } else if (event.type === 'MESSAGE_EDITED') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number;
        const convId = payload.conversationId as number;
        const newCiphertext = (payload.ciphertext as string) || '';
        const newNonce = (payload.nonce as string) || '';
        const editedAt = payload.editedAt as string | undefined;

        const activeConv = activeConversationRef.current;
        if (activeConv && activeConv.id === convId) {
          const peerUserId = getOtherParticipant(activeConv, currentUser.id)?.id;
          const { decryptedContent, decryptionError } = await reDecryptEditedMessage(
            newCiphertext,
            newNonce,
            currentUser.id,
            peerUserId
          );

          setMessages((prev) => {
            const updated = prev.map((m) =>
              m.id === msgId
                ? { ...m, ciphertext: newCiphertext, nonce: newNonce, editedAt, decryptedContent, decryptionError }
                : m
            );
            conversationCache.setConversation(convId, {
              messages: updated,
              hasMore: conversationCache.getConversation(convId)?.hasMore ?? false,
              oldestCursor: conversationCache.getConversation(convId)?.oldestCursor ?? null,
            });
            return updated;
          });

          setPinnedMessage((prev) =>
            prev && prev.id === msgId ? { ...prev, ciphertext: newCiphertext, nonce: newNonce, editedAt, decryptedContent, decryptionError } : prev
          );
        }
      } else if (event.type === 'MESSAGE_DELETED') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number;
        const convId = payload.conversationId as number;

        const activeConv = activeConversationRef.current;
        if (activeConv && activeConv.id === convId) {
          setMessages((prev) => {
            const updated = prev.map((m) =>
              m.id === msgId
                ? { ...m, deletedForEveryone: true, pinnedAt: undefined, pinnedByUserId: undefined, pinnedByUsername: undefined }
                : m
            );
            conversationCache.setConversation(convId, {
              messages: updated,
              hasMore: conversationCache.getConversation(convId)?.hasMore ?? false,
              oldestCursor: conversationCache.getConversation(convId)?.oldestCursor ?? null,
            });
            return updated;
          });
        }
        setPinnedMessage((prev) => (prev && prev.id === msgId ? null : prev));
      } else if (event.type === 'MESSAGE_PINNED' || event.type === 'MESSAGE_UNPINNED') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number;
        const convId = payload.conversationId as number;
        const isPinned = event.type === 'MESSAGE_PINNED';
        const pinnedAt = payload.pinnedAt as string | undefined;
        const pinnedByUserId = payload.pinnedByUserId as number | undefined;
        const pinnedByUsername = payload.pinnedByUsername as string | undefined;

        const activeConv = activeConversationRef.current;
        if (activeConv && activeConv.id === convId) {
          setMessages((prev) => {
            const updated = prev.map((m) =>
              m.id === msgId
                ? {
                    ...m,
                    pinnedAt: isPinned ? pinnedAt : undefined,
                    pinnedByUserId: isPinned ? pinnedByUserId : undefined,
                    pinnedByUsername: isPinned ? pinnedByUsername : undefined,
                  }
                : m
            );
            conversationCache.setConversation(convId, {
              messages: updated,
              hasMore: conversationCache.getConversation(convId)?.hasMore ?? false,
              oldestCursor: conversationCache.getConversation(convId)?.oldestCursor ?? null,
            });
            return updated;
          });

          // Re-fetch the header's pinned-message summary rather than reconstructing it
          // from local state, which may not have the target message loaded (e.g. it's
          // further back in history than the current pagination window).
          refreshPinnedMessage(convId);
        }
      } else if (event.type === 'READ_RECEIPT_UPDATE') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number | undefined;
        const msgIds = payload.messageIds as number[] | undefined;
        const convId = payload.conversationId as number | undefined;
        const deliveredAt = payload.deliveredAt as string | undefined;
        const readAt = payload.readAt as string | undefined;

        const targetIdSet = new Set<number>();
        if (Array.isArray(msgIds)) {
          msgIds.forEach((id) => targetIdSet.add(id));
        }
        if (typeof msgId === 'number') {
          targetIdSet.add(msgId);
        }

        setMessages((prev) => {
          let hasChanges = false;
          const updated = prev.map((msg) => {
            const isTarget =
              targetIdSet.has(msg.id) ||
              (targetIdSet.size === 0 && convId && msg.conversationId === convId && msg.senderUserId === currentUser.id);

            if (isTarget) {
              const newDeliveredAt = deliveredAt || msg.deliveredAt;
              const newReadAt = readAt || msg.readAt;

              // Deduplication: Only create new object if timestamps actually changed
              if (newDeliveredAt !== msg.deliveredAt || newReadAt !== msg.readAt) {
                hasChanges = true;
                return {
                  ...msg,
                  deliveredAt: newDeliveredAt,
                  readAt: newReadAt,
                };
              }
            }
            return msg;
          });

          if (!hasChanges) {
            return prev; // Prevents redundant React re-renders on duplicate receipts
          }

          const activeConv = activeConversationRef.current;
          if (activeConv) {
            conversationCache.setConversation(activeConv.id, {
              messages: updated,
              hasMore: conversationCache.getConversation(activeConv.id)?.hasMore ?? false,
              oldestCursor: conversationCache.getConversation(activeConv.id)?.oldestCursor ?? null,
            });
          }
          return updated;
        });
      } else if (event.type === 'MESSAGE_ACK') {
        const payload = event.payload as Record<string, unknown>;
        const convId = payload.conversationId as number;
        const msgId = payload.messageId as number | undefined;
        const clientTempId = payload.clientTempId as string | undefined;

        if (activeConversationRef.current?.id === convId) {
          setMessages((prev) => {
            // The blanket "any still-pending send" fallback is only safe when the ACK
            // itself carries no identifier to match against — if it does carry one,
            // matching strictly on that identifier is required, otherwise two sends
            // in flight at once can both collapse onto whichever ACK arrives first.
            const payloadHasIdentifier = Boolean(clientTempId) || msgId !== undefined;
            let fallbackApplied = false;
            const updated = prev.map((m) => {
              const isSpecificMatch =
                (clientTempId && m.clientTempId === clientTempId) || (msgId !== undefined && m.id === msgId);
              const isFallbackMatch =
                !payloadHasIdentifier && !fallbackApplied && m.id < 0 && m.status === 'SENDING';

              if (isSpecificMatch || isFallbackMatch) {
                if (isFallbackMatch) fallbackApplied = true;
                return {
                  ...m,
                  id: msgId ?? m.id,
                  status: 'SENT' as const,
                };
              }
              return m;
            });
            conversationCache.setConversation(convId, {
              messages: updated,
              hasMore: conversationCache.getConversation(convId)?.hasMore ?? false,
              oldestCursor: conversationCache.getConversation(convId)?.oldestCursor ?? null,
            });
            return updated;
          });
        }
      } else if (event.type === 'CONVERSATION_RESTORED') {
        const restoredConvId = event.payload.conversationId as number;
        loadConversations();
        if (activeConversationRef.current?.id === restoredConvId) {
          if (abortControllerRef.current) {
            abortControllerRef.current.abort();
          }
          const controller = new AbortController();
          abortControllerRef.current = controller;
          fetchAndSetMessagesForConversation(restoredConvId, ++activeRequestSeqRef.current, controller.signal);
        }
      } else if (event.type === 'CONVERSATION_DELETED') {
        const targetConvId = event.payload.conversationId as number;
        pinnedConversationsRef.current.delete(targetConvId);
        conversationCache.removeConversation(targetConvId);
        setConversations((prev) => prev.filter((c) => c.id !== targetConvId));
        setConversationPreviews((prev) => {
          const next = { ...prev };
          delete next[targetConvId];
          return next;
        });
        if (activeConversationRef.current?.id === targetConvId) {
          setActiveConversation(null);
          setMessages([]);
        }
      } else if (event.type === 'CONVERSATION_CLEARED') {
        const targetConvId = event.payload.conversationId as number;
        conversationCache.removeConversation(targetConvId);
        setConversationPreviews((prev) => {
          const next = { ...prev };
          delete next[targetConvId];
          return next;
        });
        setConversations((prev) =>
          prev.map((c) => (c.id === targetConvId ? withoutLastMessagePreview(c) : c))
        );
        if (activeConversationRef.current?.id === targetConvId) {
          setActiveConversation((prev) => (prev ? withoutLastMessagePreview(prev) : null));
          setMessages([]);
        }
      } else if (event.type === 'TYPING_INDICATOR') {
        const payload = event.payload as Record<string, unknown>;
        const convId = payload.conversationId as number;
        const senderUsername = payload.senderUsername as string;
        const isTyping = payload.isTyping as boolean;

        if (typingTimersRef.current[convId]) {
          clearTimeout(typingTimersRef.current[convId]);
          delete typingTimersRef.current[convId];
        }

        if (isTyping) {
          setTypingByConversation((prev) => ({ ...prev, [convId]: senderUsername }));
          // Safety net in case the corresponding "stopped typing" event is ever lost.
          typingTimersRef.current[convId] = setTimeout(() => {
            setTypingByConversation((prev) => {
              const next = { ...prev };
              delete next[convId];
              return next;
            });
          }, 5000);
        } else {
          setTypingByConversation((prev) => {
            if (!(convId in prev)) return prev;
            const next = { ...prev };
            delete next[convId];
            return next;
          });
        }
      } else if (event.type === 'PRESENCE_UPDATE') {
        const payload = event.payload as Record<string, unknown>;
        const userId = payload.userId as number;
        const status = payload.status as User['status'];
        const lastSeenAt = payload.lastSeenAt as string | undefined;

        const patchUser = (user: User): User =>
          user.id === userId ? { ...user, status, lastSeenAt: lastSeenAt ?? user.lastSeenAt } : user;
        const patchConversation = (conv: Conversation): Conversation => ({
          ...conv,
          members: conv.members?.map((m) => (m.user ? { ...m, user: patchUser(m.user) } : m)),
        });

        setConversations((prev) => prev.map(patchConversation));
        setActiveConversation((prev) => (prev ? patchConversation(prev) : prev));
      }
    });

    return () => unsubscribe();
  }, [
    currentUser,
    subscribe,
    fetchAndSetMessagesForConversation,
    loadConversations,
    decryptSingleMessage,
    reDecryptEditedMessage,
    refreshPinnedMessage,
    updatePreviewIfNewer,
    upsertConversation,
  ]);

  const handleOptimisticMessage = (
    plaintext: string,
    ciphertext: string,
    nonce: string,
    recipientDeviceId: number,
    replyToMessageId?: number,
    clientTempId?: string
  ) => {
    if (!activeConversation || !currentUser) return;
    const tempIdStr = clientTempId || `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    const tempMessage: Message = {
      id: nextOptimisticId(),
      clientTempId: tempIdStr,
      conversationId: activeConversation.id,
      senderUserId: currentUser.id,
      senderUsername: currentUser.username,
      messageType: 'TEXT',
      recipientDeviceId,
      encryptionAlgorithm: 'ECDH-P256+AES-256-GCM',
      ciphertext,
      nonce,
      sentAt: new Date().toISOString(),
      deletedForEveryone: false,
      decryptedContent: plaintext,
      decryptionError: false,
      replyToMessageId,
      status: 'SENDING',
    };

    setMessages((prev) => {
      const next = sortMessages([...prev, tempMessage]);
      conversationCache.setConversation(activeConversation.id, {
        messages: next,
        hasMore: conversationCache.getConversation(activeConversation.id)?.hasMore ?? false,
        oldestCursor: conversationCache.getConversation(activeConversation.id)?.oldestCursor ?? null,
      });
      return next;
    });
    updatePreviewIfNewer(activeConversation.id, previewFromMessage(tempMessage, plaintext));

    upsertConversation({
      ...activeConversation,
      lastMessageSentAt: tempMessage.sentAt,
      lastMessageSenderUserId: currentUser.id,
      lastMessageType: 'TEXT',
      updatedAt: tempMessage.sentAt,
    });
  };

  const handleOptimisticImageMessage = (
    mediaId: number,
    caption: string | undefined,
    localPreviewUrl: string,
    mimeType: string,
    replyToMessageId?: number,
    clientTempId?: string
  ) => {
    if (!activeConversation || !currentUser) return;
    const tempIdStr = clientTempId || `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    const tempMessage: Message = {
      id: nextOptimisticId(),
      clientTempId: tempIdStr,
      conversationId: activeConversation.id,
      senderUserId: currentUser.id,
      senderUsername: currentUser.username,
      messageType: 'IMAGE',
      mediaId,
      caption,
      mimeType,
      encryptionAlgorithm: 'NONE',
      ciphertext: '',
      nonce: '',
      sentAt: new Date().toISOString(),
      deletedForEveryone: false,
      localMediaUrl: localPreviewUrl,
      replyToMessageId,
      status: 'SENDING',
    };

    setMessages((prev) => {
      const next = sortMessages([...prev, tempMessage]);
      conversationCache.setConversation(activeConversation.id, {
        messages: next,
        hasMore: conversationCache.getConversation(activeConversation.id)?.hasMore ?? false,
        oldestCursor: conversationCache.getConversation(activeConversation.id)?.oldestCursor ?? null,
      });
      return next;
    });
    updatePreviewIfNewer(activeConversation.id, previewFromMessage(tempMessage, getImagePreviewText(caption)));

    upsertConversation({
      ...activeConversation,
      lastMessageSentAt: tempMessage.sentAt,
      lastMessageSenderUserId: currentUser.id,
      lastMessageType: 'IMAGE',
      lastMessageCaption: caption,
      updatedAt: tempMessage.sentAt,
    });
  };

  const handleOptimisticDocumentMessage = (
    mediaId: number,
    filename: string,
    mimeType: string,
    fileSizeBytes: number,
    replyToMessageId?: number,
    clientTempId?: string
  ) => {
    if (!activeConversation || !currentUser) return;
    const tempIdStr = clientTempId || `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    const tempMessage: Message = {
      id: nextOptimisticId(),
      clientTempId: tempIdStr,
      conversationId: activeConversation.id,
      senderUserId: currentUser.id,
      senderUsername: currentUser.username,
      messageType: 'DOCUMENT',
      mediaId,
      caption: filename,
      mimeType,
      fileSizeBytes,
      encryptionAlgorithm: 'NONE',
      ciphertext: '',
      nonce: '',
      sentAt: new Date().toISOString(),
      deletedForEveryone: false,
      replyToMessageId,
      status: 'SENDING',
    };

    setMessages((prev) => {
      const next = sortMessages([...prev, tempMessage]);
      conversationCache.setConversation(activeConversation.id, {
        messages: next,
        hasMore: conversationCache.getConversation(activeConversation.id)?.hasMore ?? false,
        oldestCursor: conversationCache.getConversation(activeConversation.id)?.oldestCursor ?? null,
      });
      return next;
    });
    updatePreviewIfNewer(activeConversation.id, previewFromMessage(tempMessage, `📄 ${filename}`));

    upsertConversation({
      ...activeConversation,
      lastMessageSentAt: tempMessage.sentAt,
      lastMessageSenderUserId: currentUser.id,
      lastMessageType: 'DOCUMENT',
      lastMessageCaption: filename,
      updatedAt: tempMessage.sentAt,
    });
  };

  const handleOptimisticLocationMessage = (
    latitude: number,
    longitude: number,
    locationLabel: string | undefined,
    replyToMessageId?: number,
    clientTempId?: string
  ) => {
    if (!activeConversation || !currentUser) return;
    const tempIdStr = clientTempId || `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    const tempMessage: Message = {
      id: nextOptimisticId(),
      clientTempId: tempIdStr,
      conversationId: activeConversation.id,
      senderUserId: currentUser.id,
      senderUsername: currentUser.username,
      messageType: 'LOCATION',
      latitude,
      longitude,
      locationLabel,
      encryptionAlgorithm: 'NONE',
      ciphertext: '',
      nonce: '',
      sentAt: new Date().toISOString(),
      deletedForEveryone: false,
      replyToMessageId,
      status: 'SENDING',
    };

    setMessages((prev) => {
      const next = sortMessages([...prev, tempMessage]);
      conversationCache.setConversation(activeConversation.id, {
        messages: next,
        hasMore: conversationCache.getConversation(activeConversation.id)?.hasMore ?? false,
        oldestCursor: conversationCache.getConversation(activeConversation.id)?.oldestCursor ?? null,
      });
      return next;
    });
    updatePreviewIfNewer(activeConversation.id, previewFromMessage(tempMessage));

    upsertConversation({
      ...activeConversation,
      lastMessageSentAt: tempMessage.sentAt,
      lastMessageSenderUserId: currentUser.id,
      lastMessageType: 'LOCATION',
      lastMessageCaption: locationLabel,
      updatedAt: tempMessage.sentAt,
    });
  };

  const handleMessageSent = useCallback(() => {
    // Zero full reload on send!
  }, []);

  const handleReactMessage = useCallback(async (messageId: number, reaction: string) => {
    try {
      await messageApi.addReaction(messageId, reaction);
    } catch (err: unknown) {
      console.error('Failed to update reaction:', err);
    }
  }, []);

  const handleEditMessage = useCallback(
    async (messageId: number, newPlaintext: string) => {
      const activeConv = activeConversationRef.current;
      if (!activeConv || !currentUser) return;

      const peerUser = getOtherParticipant(activeConv, currentUser.id);
      if (!peerUser) {
        throw new Error('Recipient not found for this conversation.');
      }

      let recipientPublicKeys = conversationCache.getPublicKeys(peerUser.id);
      if (!recipientPublicKeys || recipientPublicKeys.length === 0) {
        recipientPublicKeys = await deviceApi.getUserPublicKeys(peerUser.id);
        if (recipientPublicKeys && recipientPublicKeys.length > 0) {
          conversationCache.setPublicKeys(peerUser.id, recipientPublicKeys);
        }
      }
      if (!recipientPublicKeys || recipientPublicKeys.length === 0) {
        throw new Error('Recipient has no registered public keys on the server.');
      }

      const senderPrivateKey = await keyManager.getPrivateKey(currentUser.id);
      if (!senderPrivateKey) {
        throw new Error('Sender private key is missing from local browser vault.');
      }

      const encrypted = await encryptMessage(senderPrivateKey, recipientPublicKeys[0].publicKey, newPlaintext);
      const updated = await messageApi.editMessage(messageId, encrypted.ciphertext, encrypted.nonce);

      setMessages((prev) => {
        const next = prev.map((m) =>
          m.id === messageId
            ? {
                ...m,
                ciphertext: encrypted.ciphertext,
                nonce: encrypted.nonce,
                editedAt: updated.editedAt,
                decryptedContent: newPlaintext,
                decryptionError: false,
              }
            : m
        );
        conversationCache.setConversation(activeConv.id, {
          messages: next,
          hasMore: conversationCache.getConversation(activeConv.id)?.hasMore ?? false,
          oldestCursor: conversationCache.getConversation(activeConv.id)?.oldestCursor ?? null,
        });
        return next;
      });
      await keyManager.saveDecryptedMessage(messageId, newPlaintext);
    },
    [currentUser]
  );

  const handlePinMessage = useCallback(async (messageId: number) => {
    try {
      await messageApi.pinMessage(messageId);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to pin message';
      alert(message);
    }
  }, []);

  const handleUnpinMessage = useCallback(async (messageId: number) => {
    try {
      await messageApi.unpinMessage(messageId);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to unpin message';
      alert(message);
    }
  }, []);

  const handleStarMessage = useCallback(async (messageId: number) => {
    try {
      await messageApi.starMessage(messageId);
      setMessages((prev) => {
        const next = prev.map((m) => (m.id === messageId ? { ...m, starred: true } : m));
        const activeConv = activeConversationRef.current;
        if (activeConv) {
          conversationCache.setConversation(activeConv.id, {
            messages: next,
            hasMore: conversationCache.getConversation(activeConv.id)?.hasMore ?? false,
            oldestCursor: conversationCache.getConversation(activeConv.id)?.oldestCursor ?? null,
          });
        }
        return next;
      });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to star message';
      alert(message);
    }
  }, []);

  const handleUnstarMessage = useCallback(async (messageId: number) => {
    try {
      await messageApi.unstarMessage(messageId);
      setMessages((prev) => {
        const next = prev.map((m) => (m.id === messageId ? { ...m, starred: false } : m));
        const activeConv = activeConversationRef.current;
        if (activeConv) {
          conversationCache.setConversation(activeConv.id, {
            messages: next,
            hasMore: conversationCache.getConversation(activeConv.id)?.hasMore ?? false,
            oldestCursor: conversationCache.getConversation(activeConv.id)?.oldestCursor ?? null,
          });
        }
        return next;
      });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to unstar message';
      alert(message);
    }
  }, []);

  interface ForwardResult {
    conversationId: number;
    success: boolean;
    error?: string;
  }

  const handleForwardMessages = useCallback(
    async (messagesToForward: Message[], targetConversationIds: number[]): Promise<ForwardResult[]> => {
      if (!currentUser) return [];
      const results: ForwardResult[] = [];

      for (const targetConvId of targetConversationIds) {
        const targetConv = conversationsRef.current.find((c) => c.id === targetConvId);
        if (!targetConv) {
          results.push({ conversationId: targetConvId, success: false, error: 'Conversation not found' });
          continue;
        }
        const targetPeer = getOtherParticipant(targetConv, currentUser.id);

        try {
          for (const msg of messagesToForward) {
            if (msg.messageType === 'IMAGE' || msg.messageType === 'DOCUMENT') {
              await messageApi.sendMessage({
                conversationId: targetConvId,
                messageType: msg.messageType,
                mediaId: msg.mediaId,
                caption: msg.caption,
                forwarded: true,
              });
            } else if (msg.messageType === 'LOCATION') {
              await messageApi.sendMessage({
                conversationId: targetConvId,
                messageType: 'LOCATION',
                latitude: msg.latitude,
                longitude: msg.longitude,
                locationLabel: msg.locationLabel,
                forwarded: true,
              });
            } else {
              if (!targetPeer) {
                throw new Error('Recipient not found for target conversation.');
              }
              const plaintext = msg.decryptedContent;
              if (!plaintext) {
                throw new Error('Message content is unavailable to forward.');
              }

              let recipientPublicKeys = conversationCache.getPublicKeys(targetPeer.id);
              if (!recipientPublicKeys || recipientPublicKeys.length === 0) {
                recipientPublicKeys = await deviceApi.getUserPublicKeys(targetPeer.id);
                if (recipientPublicKeys && recipientPublicKeys.length > 0) {
                  conversationCache.setPublicKeys(targetPeer.id, recipientPublicKeys);
                }
              }
              if (!recipientPublicKeys || recipientPublicKeys.length === 0) {
                throw new Error("This user hasn't activated secure messaging yet.");
              }

              const senderPrivateKey = await keyManager.getPrivateKey(currentUser.id);
              if (!senderPrivateKey) {
                throw new Error('Sender private key is missing from local browser vault.');
              }
              const senderDevice = await keyManager.getLocalDevice(currentUser.id);

              const encrypted = await encryptMessage(senderPrivateKey, recipientPublicKeys[0].publicKey, plaintext);

              await messageApi.sendMessage({
                conversationId: targetConvId,
                messageType: 'TEXT',
                senderDeviceId: senderDevice?.deviceId,
                recipientDeviceId: recipientPublicKeys[0].deviceId,
                encryptionAlgorithm: 'ECDH-P256+AES-256-GCM',
                ciphertext: encrypted.ciphertext,
                nonce: encrypted.nonce,
                forwarded: true,
              });
            }
          }
          results.push({ conversationId: targetConvId, success: true });
        } catch (err: unknown) {
          const message = err instanceof Error ? err.message : 'Failed to forward message';
          results.push({ conversationId: targetConvId, success: false, error: message });
        }
      }

      return results;
    },
    [currentUser]
  );

  const handleMuteChat = async (duration: '8_HOURS' | '1_WEEK' | 'ALWAYS') => {
    if (!activeConversation) return;
    let mutedUntil: string | undefined;
    const now = Date.now();
    if (duration === '8_HOURS') {
      mutedUntil = new Date(now + 8 * 3600 * 1000).toISOString();
    } else if (duration === '1_WEEK') {
      mutedUntil = new Date(now + 7 * 24 * 3600 * 1000).toISOString();
    } else {
      mutedUntil = '9999-12-31T23:59:59Z';
    }

    try {
      await conversationApi.muteConversation(activeConversation.id, mutedUntil);
      const updatedConv = {
        ...activeConversation,
        isMuted: true,
        mutedUntil,
      };
      upsertConversation(updatedConv);
      setActiveConversation(updatedConv);
    } catch (err) {
      console.error('Failed to mute conversation:', err);
      alert('Failed to mute conversation');
    }
  };

  const handleUnmuteChat = async () => {
    if (!activeConversation) return;
    try {
      await conversationApi.unmuteConversation(activeConversation.id);
      const updatedConv = {
        ...activeConversation,
        isMuted: false,
        mutedUntil: undefined,
      };
      upsertConversation(updatedConv);
      setActiveConversation(updatedConv);
    } catch (err) {
      console.error('Failed to unmute conversation:', err);
      alert('Failed to unmute conversation');
    }
  };

  const handleDeleteMessage = useCallback(async (messageId: number, deleteForEveryone: boolean) => {
    try {
      await messageApi.deleteMessage(messageId, deleteForEveryone);
      setMessages((prev) => {
        const next = deleteForEveryone
          ? prev.map((m) => (m.id === messageId ? { ...m, deletedForEveryone: true } : m))
          : prev.filter((m) => m.id !== messageId);
        const activeConv = activeConversationRef.current;
        if (activeConv) {
          conversationCache.setConversation(activeConv.id, {
            messages: next,
            hasMore: conversationCache.getConversation(activeConv.id)?.hasMore ?? false,
            oldestCursor: conversationCache.getConversation(activeConv.id)?.oldestCursor ?? null,
          });
        }
        return next;
      });
    } catch (err: any) {
      alert('Failed to delete message: ' + err.message);
    }
  }, []);

  const handleDeleteConversation = async (conversationId: number) => {
    if (!window.confirm('Delete this conversation from your list?')) return;
    try {
      await conversationApi.deleteConversation(conversationId);
      pinnedConversationsRef.current.delete(conversationId);
      conversationCache.removeConversation(conversationId);
      setConversations((prev) => prev.filter((c) => c.id !== conversationId));
      setConversationPreviews((prev) => {
        const next = { ...prev };
        delete next[conversationId];
        return next;
      });
      if (activeConversation?.id === conversationId) {
        setActiveConversation(null);
        setMessages([]);
      }
    } catch (err: any) {
      alert('Failed to delete conversation: ' + err.message);
    }
  };

  const handleClearChat = async () => {
    if (!activeConversation) return;

    const conversationId = activeConversation.id;
    await conversationApi.clearConversation(conversationId);
    conversationCache.removeConversation(conversationId);

    setMessages([]);
    setConversationPreviews((prev) => {
      const next = { ...prev };
      delete next[conversationId];
      return next;
    });
    setConversations((prev) =>
      prev.map((c) => (c.id === conversationId ? withoutLastMessagePreview(c) : c))
    );
    setActiveConversation((prev) => (prev ? withoutLastMessagePreview(prev) : null));
  };

  const handleSelectConversation = useCallback(
    (conv: Conversation) => {
      // 1. Immediately switch active conversation synchronously (0 blocking network calls).
      // Both refs are set here, not just the id — activeConversationRef would otherwise only
      // catch up via the [activeConversation] effect below, leaving a window where the two
      // refs disagree about which conversation is active.
      setActiveConversation(conv);
      activeConversationIdRef.current = conv.id;
      activeConversationRef.current = conv;

      // 2. Abort any previous pending message request to prevent race conditions
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
        abortControllerRef.current = null;
      }
      const requestSeq = ++activeRequestSeqRef.current;
      const controller = new AbortController();
      abortControllerRef.current = controller;

      // 3. Immediately isolate conversation state: check LRU cache or blank out messages
      const cached = conversationCache.getConversation(conv.id);
      if (cached && cached.messages.length > 0) {
        // Cache HIT: render immediately in 0ms from memory (0 network requests, 0 full re-decryptions)
        setMessages(cached.messages);
        setHasMoreMessages(cached.hasMore);

        // Check if there are newer messages on the server that might have arrived during disconnection
        const latestCachedId = cached.messages.reduce((max, m) => (m.id > max ? m.id : max), 0);
        if (conv.lastMessageId && conv.lastMessageId > latestCachedId) {
          // Stale cache: perform lightweight background synchronization without blocking the UI
          fetchAndSetMessagesForConversation(conv.id, requestSeq, controller.signal);
        }
      } else {
        // Cache MISS: Instantly clear messages so Person A's messages NEVER bleed into Person B's chat window
        setMessages([]);
        setHasMoreMessages(false);

        // Fetch the initial paged window from backend
        fetchAndSetMessagesForConversation(conv.id, requestSeq, controller.signal);
      }
    },
    [fetchAndSetMessagesForConversation]
  );

  const handleUserUpdated = useCallback((updatedUser: User) => {
    setCurrentUser(updatedUser);
    localStorage.setItem('connectx_user', JSON.stringify(updatedUser));

    const updateMembers = (conv: Conversation): Conversation => ({
      ...conv,
      members: conv.members?.map((member) =>
        member.user?.id === updatedUser.id
          ? { ...member, user: { ...member.user, ...updatedUser } }
          : member
      ),
    });

    setConversations((prev) => prev.map(updateMembers));
    setActiveConversation((prev) => (prev ? updateMembers(prev) : null));
  }, []);

  const handlePinConversation = async (conversationId: number) => {
    try {
      const updated = await conversationApi.pinConversation(conversationId);
      upsertConversation(updated);
      if (activeConversationRef.current?.id === conversationId) {
        setActiveConversation(updated);
      }
      loadConversations();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to pin conversation';
      alert(message);
    }
  };

  const handleUnpinConversation = async (conversationId: number) => {
    try {
      const updated = await conversationApi.unpinConversation(conversationId);
      upsertConversation(updated);
      if (activeConversationRef.current?.id === conversationId) {
        setActiveConversation(updated);
      }
      loadConversations();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to unpin conversation';
      alert(message);
    }
  };

  const handleArchiveConversation = async (conversationId: number) => {
    try {
      const updated = await conversationApi.archiveConversation(conversationId);
      upsertConversation(updated);
      if (activeConversationRef.current?.id === conversationId) {
        setActiveConversation(updated);
      }
      loadConversations();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to archive conversation';
      alert(message);
    }
  };

  const handleUnarchiveConversation = async (conversationId: number) => {
    try {
      const updated = await conversationApi.unarchiveConversation(conversationId);
      upsertConversation(updated);
      if (activeConversationRef.current?.id === conversationId) {
        setActiveConversation(updated);
      }
      loadConversations();
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to unarchive conversation';
      alert(message);
    }
  };

  // Deliberately does NOT sync `activeConversation` even if this is the open chat —
  // ChatScreen never reads `manuallyMarkedUnread`, and syncing it would immediately
  // re-trigger the "opening a conversation clears unread" effect, undoing this call.
  const handleMarkUnread = async (conversationId: number) => {
    try {
      const updated = await conversationApi.markUnread(conversationId);
      upsertConversation(updated);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to mark conversation unread';
      alert(message);
    }
  };

  const handleMarkRead = async (conversationId: number) => {
    try {
      const updated = await conversationApi.markRead(conversationId);
      upsertConversation(updated);
    } catch (err: unknown) {
      console.warn('[ConnectX] Failed to mark conversation read:', err);
    }
  };

  const getRecipientUser = (conv: Conversation | null): User | null => {
    if (!conv || !currentUser) return null;
    return getOtherParticipant(conv, currentUser.id);
  };

  if (!currentUser) {
    return <AuthModal onSuccess={handleAuthSuccess} />;
  }

  return (
    <div className="app-shell flex flex-col bg-slate-100 dark:bg-[#090d16] transition-colors duration-300">
      {status !== 'CONNECTED' && (
        <div className="flex-shrink-0 w-full bg-amber-500/15 dark:bg-amber-950/40 border-b border-amber-500/30 text-amber-700 dark:text-amber-300 text-xs py-1 px-3 text-center font-medium flex items-center justify-center gap-2 select-none z-50">
          <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
          <span>Connecting to server...</span>
        </div>
      )}
      {pendingShare && !activeConversation && (
        <div className="flex-shrink-0 w-full bg-indigo-500/15 dark:bg-indigo-950/40 border-b border-indigo-500/30 text-indigo-700 dark:text-indigo-300 text-xs py-1.5 px-3 text-center font-medium flex items-center justify-center gap-2 select-none z-50">
          <Share2 className="w-3.5 h-3.5 flex-shrink-0" />
          <span>
            Choose a conversation to share {pendingShare.files.length}{' '}
            {pendingShare.files.length === 1 ? 'file' : 'files'}
          </span>
          <button
            type="button"
            onClick={() => setPendingShare(null)}
            className="p-0.5 rounded-full hover:bg-indigo-500/20 transition-colors"
            aria-label="Cancel share"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}
      <div className="flex-1 min-h-0 w-full flex overflow-hidden">
        {/* Conversation list — full screen on mobile when no chat selected */}
        <div
          className={`h-full flex-shrink-0 ${
            activeConversation ? 'hidden md:flex' : 'flex w-full md:w-auto'
          }`}
        >
        <ChatListSidebar
          currentUser={currentUser}
          conversations={conversations}
          activeConversationId={activeConversation?.id || null}
          unreadConversationIds={unreadConversationIds}
          conversationPreviews={conversationPreviews}
          onSelectConversation={handleSelectConversation}
          onOpenSearch={() => setShowSearchModal(true)}
          onOpenProfile={async () => {
            try {
              const freshUser = await userApi.getCurrentUser();
              handleUserUpdated(freshUser);
            } catch (err) {
              console.warn('[ConnectX] Failed to refresh profile before opening menu:', err);
            }
            setShowProfileModal(true);
          }}
          onDeleteConversation={handleDeleteConversation}
          onPinConversation={handlePinConversation}
          onUnpinConversation={handleUnpinConversation}
          onArchiveConversation={handleArchiveConversation}
          onUnarchiveConversation={handleUnarchiveConversation}
          onMarkUnread={handleMarkUnread}
          onMarkRead={handleMarkRead}
        />
      </div>

      {/* Chat panel — full screen on mobile when conversation open */}
      <div
        className={`flex-1 min-w-0 min-h-0 flex flex-col ${
          activeConversation ? 'flex' : 'hidden md:flex'
        }`}
      >
        {activeConversation ? (
          <div className="flex h-full w-full min-h-0 min-w-0">
            <ChatScreen
              key={activeConversation.id}
              recipient={getRecipientUser(activeConversation)}
              conversationId={activeConversation.id}
              messages={messages}
              currentUserId={currentUser.id}
              showRawCiphertext={showRawCiphertext}
              showInfoDrawer={showInfoDrawer}
              isDarkMode={isDarkMode}
              isMuted={Boolean(
                activeConversation.isMuted ||
                (activeConversation.mutedUntil &&
                  new Date(activeConversation.mutedUntil).getTime() > Date.now())
              )}
              isTyping={Boolean(typingByConversation[activeConversation.id])}
              hasMore={hasMoreMessages}
              isLoadingOlder={isLoadingOlder}
              onLoadOlderMessages={loadOlderMessages}
              onToggleCiphertext={() => setShowRawCiphertext(!showRawCiphertext)}
              onToggleInfoDrawer={() => setShowInfoDrawer(!showInfoDrawer)}
              onBack={() => {
                if (activeConversation) {
                  upsertConversation(activeConversation);
                }
                setActiveConversation(null);
                setShowInfoDrawer(false);
                loadConversations();
              }}
              onDeleteMessage={handleDeleteMessage}
              onOptimisticMessage={handleOptimisticMessage}
              onOptimisticImageMessage={handleOptimisticImageMessage}
              onOptimisticLocationMessage={handleOptimisticLocationMessage}
              onOptimisticDocumentMessage={handleOptimisticDocumentMessage}
              onMessageSent={handleMessageSent}
              onClearChat={handleClearChat}
              onMuteChat={handleMuteChat}
              onUnmuteChat={handleUnmuteChat}
              onReactMessage={handleReactMessage}
              onEditMessage={handleEditMessage}
              pinnedMessage={pinnedMessage}
              onPinMessage={handlePinMessage}
              onUnpinMessage={handleUnpinMessage}
              onStarMessage={handleStarMessage}
              onUnstarMessage={handleUnstarMessage}
              onForwardMessages={handleForwardMessages}
              conversations={conversations}
              initialSharedMedia={pendingShare ? splitSharedFiles(pendingShare.files) : null}
              onSharedMediaConsumed={() => setPendingShare(null)}
            />

            {showInfoDrawer && (
              <React.Suspense fallback={null}>
                <ContactInfoDrawer
                  recipient={getRecipientUser(activeConversation)}
                  onClose={() => setShowInfoDrawer(false)}
                />
              </React.Suspense>
            )}
          </div>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-center select-none">
            <div className="max-w-sm space-y-5">
              <div className="w-16 h-16 rounded-2xl bg-indigo-600/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400 mx-auto">
                <MessageSquare className="w-8 h-8" />
              </div>
              <div className="space-y-2">
                <h2 className="text-xl font-bold text-slate-900 dark:text-white">ConnectX Messaging</h2>
                <p className="text-sm text-slate-500 dark:text-slate-400 leading-relaxed">
                  Select a conversation or start a new encrypted chat.
                </p>
              </div>
              <button
                onClick={() => setShowSearchModal(true)}
                className="px-5 py-2.5 rounded-full bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold shadow-md inline-flex items-center gap-2 transition-colors"
              >
                <Plus className="w-4 h-4" />
                New conversation
              </button>
            </div>
          </div>
        )}
      </div>
      </div>

      {showProfileModal && (
        <React.Suspense fallback={null}>
          <ProfileModal
            currentUser={currentUser}
            isDarkMode={isDarkMode}
            onToggleTheme={() => setIsDarkMode(!isDarkMode)}
            onOpenDevices={() => setShowDeviceModal(true)}
            onClose={() => setShowProfileModal(false)}
            onLogout={handleLogout}
            onUserUpdated={handleUserUpdated}
          />
        </React.Suspense>
      )}

      {showSearchModal && (
        <React.Suspense fallback={null}>
          <UserSearchModal
            onClose={() => setShowSearchModal(false)}
            onSelectConversation={(conv) => {
              upsertConversation(conv);
              setActiveConversation(conv);
              loadConversations();
            }}
          />
        </React.Suspense>
      )}

      {showDeviceModal && (
        <React.Suspense fallback={null}>
          <DeviceManagerModal currentUser={currentUser} onClose={() => setShowDeviceModal(false)} />
        </React.Suspense>
      )}

      <NotificationToast
        toast={currentToast}
        onDismiss={() => setCurrentToast(null)}
        onClickToast={handleSelectToastConversation}
      />

      <PWAInstallBanner />
    </div>
  );
};
