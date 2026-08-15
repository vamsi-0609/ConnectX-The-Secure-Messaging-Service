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
  const conversationsLoadSeqRef = useRef(0);
  const pinnedConversationsRef = useRef<Map<number, Conversation>>(new Map());

  useEffect(() => {
    activeConversationRef.current = activeConversation;
    activeConversationIdRef.current = activeConversation?.id ?? null;

    if (activeConversation) {
      wsClient.subscribeToConversation(activeConversation.id);
      setUnreadConversationIds((prev) => {
        const next = new Set(prev);
        next.delete(activeConversation.id);
        return next;
      });
      wsClient.sendRead(activeConversation.id);
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
          console.warn('[ConnectX Auth] Session validation encountered error:', err);
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
      console.error('[ConnectX] Failed to load user conversations:', err);
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

        const userKeys = await deviceApi.getUserPublicKeys(resolvedPeerUserId);
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

  const loadMessages = useCallback(
    async (conversationId?: number, mode: 'replace' | 'merge' = 'merge') => {
      const convId = conversationId ?? activeConversationIdRef.current;
      if (!convId || !currentUser) return;

      try {
        const rawMsgs = await messageApi.getMessages(convId);
        const activeConv = conversationsRef.current.find((c) => c.id === convId) ?? activeConversationRef.current;
        const peerUserId = activeConv ? getOtherParticipant(activeConv, currentUser.id)?.id : undefined;

        const decryptedList = await Promise.all(
          rawMsgs.map((m) => decryptSingleMessage(m, currentUser.id, peerUserId))
        );

        if (mode === 'replace') {
          setMessages(sortMessages(decryptedList));
        } else {
          setMessages((prev) => mergeMessagesForConversation(convId, decryptedList, prev));
        }

        if (decryptedList.length > 0) {
          const last = decryptedList[decryptedList.length - 1];
          updatePreviewIfNewer(convId, previewFromMessage(last));
        } else {
          setConversationPreviews((prev) => {
            const next = { ...prev };
            delete next[convId];
            return next;
          });
        }
      } catch (err) {
        console.error('[ConnectX] Failed to load messages for conversation:', err);
      }
    },
    [currentUser, decryptSingleMessage, updatePreviewIfNewer]
  );

  useEffect(() => {
    if (activeConversation) {
      loadMessages(activeConversation.id, 'replace');
    } else {
      setMessages([]);
    }
  }, [activeConversation?.id, loadMessages]);

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
            const newMsg = messageFromWsPayload(payload as Record<string, unknown>);
            const peerUserId = getOtherParticipant(currentActive, currentUser.id)?.id;
            const processedMsg =
              newMsg.messageType === 'IMAGE' || newMsg.messageType === 'LOCATION' || newMsg.messageType === 'DOCUMENT'
                ? newMsg
                : await decryptSingleMessage(newMsg, currentUser.id, peerUserId);

            setMessages((prev) => {
              if (prev.some((m) => m.id === processedMsg.id)) return prev;
              return sortMessages([...prev.filter((m) => m.conversationId === conversationId), processedMsg]);
            });

            const preview = previewFromMessage(processedMsg);
            updatePreviewIfNewer(conversationId, preview);

            wsClient.sendDelivered(payload.messageId as number);
            // Mark the whole conversation as read since we're actively viewing it
            wsClient.sendRead(conversationId);

            const isMuted =
              currentActive.isMuted ||
              (currentActive.mutedUntil && new Date(currentActive.mutedUntil).getTime() > Date.now());

            // Play incoming audio chime only if not muted
            if (!isMuted) {
              soundManager.playIncomingMessageSound();
            }
          } else {
            loadMessages(conversationId, 'merge');
          }
        } else {
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

          if (payload.senderUserId !== currentUser.id) {
            setUnreadConversationIds((prev) => new Set(prev).add(conversationId));
            wsClient.sendDelivered(payload.messageId as number);

            const isMuted =
              conv?.isMuted ||
              (conv?.mutedUntil && new Date(conv.mutedUntil).getTime() > Date.now());

            if (!isMuted) {
              // Play incoming audio chime
              soundManager.playIncomingMessageSound();

              const senderName = (payload.senderUsername as string) || 'ConnectX User';
              const senderAvatar = conv ? getOtherParticipant(conv, currentUser.id)?.profileImageUrl : undefined;

              // Show In-App Toast Banner
              setCurrentToast({
                id: `toast-${msgId}-${Date.now()}`,
                senderName,
                senderAvatar,
                messageText: preview.text,
                conversationId,
                timestamp: (payload.sentAt as string) || new Date().toISOString(),
              });

              // Show Browser Desktop System Push Notification
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

        loadConversations();
      } else if (event.type === 'MESSAGE_REACTION_UPDATE') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number;
        const reactions = payload.reactions as Message['reactions'];

        setMessages((prev) =>
          prev.map((msg) => {
            if (msg.id === msgId) {
              return { ...msg, reactions };
            }
            return msg;
          })
        );
      } else if (event.type === 'READ_RECEIPT_UPDATE') {
        const payload = event.payload as Record<string, unknown>;
        const msgId = payload.messageId as number | undefined;
        const convId = payload.conversationId as number | undefined;
        const deliveredAt = payload.deliveredAt as string | undefined;
        const readAt = payload.readAt as string | undefined;

        setMessages((prev) =>
          prev.map((msg) => {
            if (msg.id === msgId || (convId && msg.conversationId === convId && msg.senderUserId === currentUser.id)) {
              return {
                ...msg,
                deliveredAt: deliveredAt || msg.deliveredAt,
                readAt: readAt || msg.readAt,
              };
            }
            return msg;
          })
        );
      } else if (event.type === 'MESSAGE_ACK') {
        const conversationId = event.payload.conversationId as number;
        if (activeConversationRef.current?.id === conversationId) {
          loadMessages(conversationId, 'merge');
        }
        loadConversations();
      } else if (event.type === 'CONVERSATION_RESTORED') {
        const restoredConvId = event.payload.conversationId as number;
        await loadConversations();
        if (activeConversationRef.current?.id === restoredConvId) {
          loadMessages(restoredConvId, 'replace');
        }
      } else if (event.type === 'CONVERSATION_DELETED') {
        const targetConvId = event.payload.conversationId as number;
        pinnedConversationsRef.current.delete(targetConvId);
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
  }, [currentUser, subscribe, loadMessages, loadConversations, decryptSingleMessage, updatePreviewIfNewer, upsertConversation]);

  const handleOptimisticMessage = (
    plaintext: string,
    ciphertext: string,
    nonce: string,
    recipientDeviceId: number,
    replyToMessageId?: number
  ) => {
    if (!activeConversation || !currentUser) return;

    const tempMessage: Message = {
      id: -Date.now(),
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
    };

    setMessages((prev) => sortMessages([...prev, tempMessage]));
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
    replyToMessageId?: number
  ) => {
    if (!activeConversation || !currentUser) return;

    const tempMessage: Message = {
      id: -Date.now(),
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
    };

    setMessages((prev) => sortMessages([...prev, tempMessage]));
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
    replyToMessageId?: number
  ) => {
    if (!activeConversation || !currentUser) return;

    const tempMessage: Message = {
      id: -Date.now(),
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
    };

    setMessages((prev) => sortMessages([...prev, tempMessage]));
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
    replyToMessageId?: number
  ) => {
    if (!activeConversation || !currentUser) return;

    const tempMessage: Message = {
      id: -Date.now(),
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
    };

    setMessages((prev) => sortMessages([...prev, tempMessage]));
    updatePreviewIfNewer(
      activeConversation.id,
      previewFromMessage(tempMessage)
    );

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
    loadConversations();
  }, [loadConversations]);

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
      if (activeConversation) {
        loadMessages(activeConversation.id, 'replace');
      }
    } catch (err: any) {
      alert('Failed to delete message: ' + err.message);
    }
  };

  const handleDeleteConversation = async (conversationId: number) => {
    if (!window.confirm('Delete this conversation from your list?')) return;
    try {
      await conversationApi.deleteConversation(conversationId);
      pinnedConversationsRef.current.delete(conversationId);
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

  const handleSelectConversation = async (conv: Conversation) => {
    try {
      const fresh = await conversationApi.getConversationById(conv.id);
      upsertConversation(fresh);
      setActiveConversation(fresh);
    } catch (err: unknown) {
      console.error('[ConnectX] Failed to open conversation:', err);
      const message = err instanceof Error ? err.message : 'Failed to open conversation';
      alert(message);
    }
  };

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
    <div className="app-shell bg-slate-100 dark:bg-[#090d16] transition-colors duration-300">
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
