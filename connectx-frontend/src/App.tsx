import React, { useEffect, useState, useCallback, useRef } from 'react';
import { AuthModal } from './features/auth/AuthModal';
import { ChatListSidebar } from './components/layout/ChatListSidebar';
import { ChatScreen } from './components/chat/ChatScreen';
import { ContactInfoDrawer } from './components/chat/ContactInfoDrawer';
import { UserSearchModal } from './components/chat/UserSearchModal';
import { DeviceManagerModal } from './components/devices/DeviceManagerModal';
import { ProfileModal } from './components/profile/ProfileModal';
import { useWebSocket } from './websocket/WebSocketContext';
import { wsClient } from './websocket/WebSocketClient';
import { conversationApi } from './api/conversationApi';
import { messageApi } from './api/messageApi';
import { userApi } from './api/userApi';
import { deviceApi } from './api/deviceApi';
import { authApi } from './api/authApi';
import { keyManager } from './crypto/keyManager';
import { decryptMessage } from './crypto/decryption';
import { ensureLocalCryptoDevice } from './crypto/deviceSession';
import { conversationCache } from './cache/conversationCache';
import { applyTheme, isDarkTheme } from './utils/theme';
import { soundManager } from './utils/notificationSound';
import { browserNotifications } from './utils/browserNotifications';
import { registerWebPushSubscription } from './utils/pushSubscription';
import { NotificationToast, ToastNotificationData } from './components/common/NotificationToast';
import { PWAInstallBanner } from './components/common/PWAInstallBanner';
import { User, Conversation, Message, AuthResponse, ConversationPreview } from './types';
import { MessageSquare, Plus } from 'lucide-react';
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
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConversation, setActiveConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [hasMoreMessages, setHasMoreMessages] = useState<boolean>(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState<boolean>(false);
  const [unreadConversationIds, setUnreadConversationIds] = useState<Set<number>>(new Set());
  const [conversationPreviews, setConversationPreviews] = useState<Record<number, ConversationPreview>>({});

  const [isDarkMode, setIsDarkMode] = useState<boolean>(() => isDarkTheme());
  const [showRawCiphertext, setShowRawCiphertext] = useState(false);
  const [showInfoDrawer, setShowInfoDrawer] = useState(false);

  const [showSearchModal, setShowSearchModal] = useState(false);
  const [showDeviceModal, setShowDeviceModal] = useState(false);
  const [showProfileModal, setShowProfileModal] = useState(false);

  const { subscribe, reconnect, status } = useWebSocket();

  const activeConversationRef = useRef<Conversation | null>(null);
  const activeConversationIdRef = useRef<number | null>(null);
  const activeRequestSeqRef = useRef<number>(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const conversationsLoadSeqRef = useRef(0);
  const pinnedConversationsRef = useRef<Map<number, Conversation>>(new Map());

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
      wsClient.sendRead(activeConversation.id);
    } else {
      wsClient.setActiveConversation(null);
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

  useEffect(() => {
    const handleAuthExpired = () => {
      console.warn('[ConnectX] Authentication session expired. Resetting session state.');
      wsClient.disconnect();
      conversationCache.clearAll();
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
    try {
      await authApi.logout();
    } catch {
      // Session may already be cleared locally.
    }
    localStorage.removeItem('connectx_token');
    localStorage.removeItem('connectx_refresh_token');
    localStorage.removeItem('connectx_user');
    conversationCache.clearAll();
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

        pinnedConversationsRef.current.forEach((conv, id) => {
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
    pinnedConversationsRef.current.set(conv.id, conv);
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

  // ── P0-1: Reload conversations when WebSocket reconnects ──────────────────
  // If the WS was dropped and reconnected, fetch fresh conversations to catch
  // any messages that arrived while the socket was disconnected.
  const prevWsStatusRef = useRef<string>('');
  useEffect(() => {
    const prev = prevWsStatusRef.current;
    prevWsStatusRef.current = status;
    // Only reload on a genuine reconnect (DISCONNECTED → CONNECTED)
    if (prev === 'DISCONNECTED' && status === 'CONNECTED' && currentUser) {
      console.log('[ConnectX] WebSocket reconnected — reloading conversations to catch missed messages.');
      loadConversations();
    }
  }, [status, currentUser, loadConversations]);

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

      try {
        const myPrivateKey = await keyManager.getPrivateKey(userId);
        if (!myPrivateKey) {
          return { ...msg, decryptionError: true };
        }

        let resolvedPeerUserId = peerUserId ?? null;
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
        return { ...msg, decryptionError: true };
      }
    },
    []
  );

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

        const sorted = sortMessages(decryptedList);
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
            // Reconcile our own message that was sent optimistically
            setMessages((prev) => {
              const updated = prev.map((m) => {
                if (
                  m.id < 0 &&
                  ((payload.clientTempId && m.clientTempId === payload.clientTempId) ||
                    (payload.mediaId && m.mediaId === payload.mediaId) ||
                    (m.ciphertext && m.ciphertext === payload.ciphertext))
                ) {
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
            const updated = prev.map((m) => {
              if (
                (clientTempId && m.clientTempId === clientTempId) ||
                (msgId && m.id === msgId) ||
                (m.id < 0 && m.status === 'SENDING')
              ) {
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
      }
    });

    return () => unsubscribe();
  }, [
    currentUser,
    subscribe,
    fetchAndSetMessagesForConversation,
    loadConversations,
    decryptSingleMessage,
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
      id: -Date.now(),
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
      id: -Date.now(),
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
      id: -Date.now(),
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
      id: -Date.now(),
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

  const handleReactMessage = async (messageId: number, reaction: string) => {
    try {
      await messageApi.addReaction(messageId, reaction);
    } catch (err: unknown) {
      console.error('Failed to update reaction:', err);
    }
  };

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

  const handleDeleteMessage = async (messageId: number, deleteForEveryone: boolean) => {
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
  };

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
      // 1. Immediately switch active conversation synchronously (0 blocking network calls)
      setActiveConversation(conv);
      activeConversationIdRef.current = conv.id;

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
            />

            {showInfoDrawer && (
              <div className="hidden md:block">
                <ContactInfoDrawer
                  recipient={getRecipientUser(activeConversation)}
                  onClose={() => setShowInfoDrawer(false)}
                />
              </div>
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
        <ProfileModal
          currentUser={currentUser}
          isDarkMode={isDarkMode}
          onToggleTheme={() => setIsDarkMode(!isDarkMode)}
          onOpenDevices={() => setShowDeviceModal(true)}
          onClose={() => setShowProfileModal(false)}
          onLogout={handleLogout}
          onUserUpdated={handleUserUpdated}
        />
      )}

      {showSearchModal && (
        <UserSearchModal
          onClose={() => setShowSearchModal(false)}
          onSelectConversation={(conv) => {
            upsertConversation(conv);
            setActiveConversation(conv);
            loadConversations();
          }}
        />
      )}

      {showDeviceModal && (
        <DeviceManagerModal currentUser={currentUser} onClose={() => setShowDeviceModal(false)} />
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
