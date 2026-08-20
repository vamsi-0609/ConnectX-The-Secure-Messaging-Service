import React, { useRef, useState, useEffect } from 'react';
import {
  Send,
  Lock,
  Paperclip,
  Mic,
  Loader2,
  ImagePlus,
  Camera,
  MapPin,
  Image as ImageIcon,
  FileText,
  X,
  CornerUpLeft,
  Pencil,
  Check,
} from 'lucide-react';
import { encryptMessage } from '../../crypto/encryption';
import { encryptWithGroupKey, encryptBytesWithGroupKey } from '../../crypto/groupCrypto';
import { groupKeyManager } from '../../crypto/groupKeyManager';
import { keyManager } from '../../crypto/keyManager';
import { deviceApi } from '../../api/deviceApi';
import { messageApi } from '../../api/messageApi';
import { mediaApi } from '../../api/mediaApi';
import { MEDIA_IMAGE_ACCEPT, validateMediaImageFile } from '../../utils/mediaImage';
import { geolocationErrorMessage, resolveCurrentLocation } from '../../utils/location';
import { CameraCaptureModal } from './CameraCaptureModal';
import { MediaBatchPreviewModal } from './MediaBatchPreviewModal';
import { ReplyTarget, Message, Group } from '../../types';
import { conversationCache } from '../../cache/conversationCache';
import { wsClient } from '../../websocket/WebSocketClient';
import { activityGuard } from '../../utils/activityGuard';
import { ApiRequestError } from '../../api/apiClient';

const TYPING_IDLE_MS = 3000;

// Shared by every send path (text, image, document) so a user never sees a raw backend error code
// or crypto term (Part 13: "Never expose technical errors such as AES/ECDH/keyVersion/wrappedKey/
// nonce/HTTP 409/403"). Pure translation only -- side effects like invalidating a stale cached
// group key stay at each call site, right where the group/conversationId context already is.
function friendlySendErrorMessage(err: unknown): string {
  const message = err instanceof Error ? err.message : 'Unknown error';
  if (
    message.includes('NO_ACTIVE_CRYPTO_DEVICE') ||
    message.includes('active cryptographic devices') ||
    message.includes("hasn't activated secure messaging")
  ) {
    return "This user hasn't activated secure messaging yet.";
  }
  if (err instanceof ApiRequestError && err.code === 'NOT_CONNECTED') {
    return "You're no longer connected with this user. Send a new connection request to message them again.";
  }
  if (message === 'GROUP_KEY_UNAVAILABLE' || message === 'GROUP_INFO_UNAVAILABLE') {
    return 'Group security is being updated. Please try again shortly.';
  }
  if (err instanceof ApiRequestError && err.code === 'GROUP_KEY_VERSION_MISMATCH') {
    return 'Group security was just updated. Please try again.';
  }
  return message;
}

interface MessageInputProps {
  conversationId: number;
  // Exactly one of recipientUserId (DIRECT) or (isGroup + group) must be provided.
  recipientUserId?: number;
  isGroup?: boolean;
  group?: Group | null;
  currentUserId: number;
  replyTarget?: ReplyTarget | null;
  onCancelReply?: () => void;
  editTarget?: Message | null;
  onCancelEdit?: () => void;
  onSubmitEdit?: (newPlaintext: string) => Promise<void>;
  onOptimisticMessage: (
    plaintext: string,
    ciphertext: string,
    nonce: string,
    recipientDeviceId: number,
    replyToMessageId?: number,
    clientTempId?: string
  ) => void;
  onOptimisticMessageFailed?: (clientTempId: string) => void;
  onOptimisticImageMessage: (
    mediaId: number,
    caption: string | undefined,
    localPreviewUrl: string,
    mimeType: string,
    replyToMessageId?: number
  ) => void;
  onOptimisticLocationMessage: (
    latitude: number,
    longitude: number,
    locationLabel: string | undefined,
    replyToMessageId?: number,
    clientTempId?: string
  ) => void;
  onOptimisticDocumentMessage: (
    mediaId: number,
    filename: string,
    mimeType: string,
    fileSizeBytes: number,
    replyToMessageId?: number
  ) => void;
  onMessageSent?: () => void;
  initialSharedMedia?: { images: File[]; docs: File[] } | null;
  onSharedMediaConsumed?: () => void;
}

export const MessageInput: React.FC<MessageInputProps> = ({
  conversationId,
  recipientUserId,
  isGroup,
  group,
  currentUserId,
  replyTarget,
  onCancelReply,
  editTarget,
  onCancelEdit,
  onSubmitEdit,
  onOptimisticMessage,
  onOptimisticMessageFailed,
  onOptimisticImageMessage,
  onOptimisticLocationMessage,
  onOptimisticDocumentMessage,
  onMessageSent,
  initialSharedMedia,
  onSharedMediaConsumed,
}) => {
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [submittingEdit, setSubmittingEdit] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState<string | null>(null);
  const [sharingLocation, setSharingLocation] = useState(false);
  const [showCamera, setShowCamera] = useState(false);
  const [showMediaMenu, setShowMediaMenu] = useState(false);
  const [showGroupMediaMilestoneModal, setShowGroupMediaMilestoneModal] = useState(false);

  // Multi-file batch modal state
  const [showBatchModal, setShowBatchModal] = useState(false);
  const [pendingImages, setPendingImages] = useState<File[]>([]);
  const [pendingDocs, setPendingDocs] = useState<File[]>([]);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const docInputRef = useRef<HTMLInputElement>(null);
  const isSendingRef = useRef(false);
  const typingActiveRef = useRef(false);
  const typingIdleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const closeMediaMenu = () => setShowMediaMenu(false);

  useEffect(() => {
    if (!showGroupMediaMilestoneModal) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowGroupMediaMilestoneModal(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [showGroupMediaMilestoneModal]);

  const stopTyping = () => {
    if (typingIdleTimerRef.current) {
      clearTimeout(typingIdleTimerRef.current);
      typingIdleTimerRef.current = null;
    }
    if (typingActiveRef.current) {
      typingActiveRef.current = false;
      wsClient.sendTyping(conversationId, false);
    }
  };

  const notifyTyping = () => {
    if (!typingActiveRef.current) {
      typingActiveRef.current = true;
      wsClient.sendTyping(conversationId, true);
    }
    if (typingIdleTimerRef.current) {
      clearTimeout(typingIdleTimerRef.current);
    }
    typingIdleTimerRef.current = setTimeout(stopTyping, TYPING_IDLE_MS);
  };

  // MessageInput remounts on conversation switch (ChatScreen is keyed by
  // conversationId), so an unmount-only cleanup is sufficient here.
  useEffect(() => {
    return () => stopTyping();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reports "mid-composition / mid-send" to the PWA update flow (see
  // utils/serviceWorker.ts) so an auto-update reload can never wipe an unsent
  // draft, discard a picked-but-unsent media batch, or cut off an in-flight
  // upload/send. Reset to idle on unmount so switching conversations (or
  // navigating back to the list) doesn't permanently block updates behind a
  // composer that no longer exists.
  useEffect(() => {
    activityGuard.setBusy(
      Boolean(text.trim()) || sending || uploading || submittingEdit || sharingLocation || showBatchModal || showCamera
    );
  }, [text, sending, uploading, submittingEdit, sharingLocation, showBatchModal, showCamera]);

  useEffect(() => {
    return () => activityGuard.setBusy(false);
  }, []);

  // Runs once per conversation selection: if the user picked this conversation
  // to fulfil a pending OS share, open the same batch preview used for a manual
  // file pick, pre-loaded with the shared files. Mount-only by design — it must
  // not re-fire on prop changes, only when MessageInput itself remounts (i.e. a
  // new conversation was chosen).
  useEffect(() => {
    if (!initialSharedMedia) return;
    const { images, docs } = initialSharedMedia;
    if (images.length > 0 || docs.length > 0) {
      if (isGroup) {
        setShowGroupMediaMilestoneModal(true);
      } else {
        setPendingImages(images);
        setPendingDocs(docs);
        setShowBatchModal(true);
      }
    }
    onSharedMediaConsumed?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Focus composer when reply is activated
  useEffect(() => {
    if (replyTarget) {
      textareaRef.current?.focus();
    }
  }, [replyTarget]);

  // Preload the composer with the original text when entering edit mode
  useEffect(() => {
    if (editTarget) {
      setText(editTarget.decryptedContent || '');
      const textarea = textareaRef.current;
      if (textarea) {
        textarea.focus();
        textarea.style.height = 'auto';
        textarea.style.height = `${Math.min(textarea.scrollHeight, 140)}px`;
        const len = textarea.value.length;
        textarea.setSelectionRange(len, len);
      }
    } else {
      setText('');
    }
  }, [editTarget]);

  const sendImageFile = async (file: File) => {
    const validationError = validateMediaImageFile(file);
    if (validationError) {
      alert(validationError);
      throw new Error(validationError);
    }

    const caption = text.trim() || undefined;
    const localPreviewUrl = URL.createObjectURL(file);
    const replyToId = replyTarget?.messageId;

    setUploading(true);
    setUploadStatus(`Uploading image ${file.name}...`);

    try {
      let mediaId: number;
      let mimeType: string;

      if (isGroup) {
        if (!group) {
          throw new Error('GROUP_INFO_UNAVAILABLE');
        }
        // Same deterministic rotation-trigger call site TEXT already uses for a GROUP send --
        // never a passive resolveGroupKey here (see groupKeyManager's own doc on why).
        const groupKey = await groupKeyManager.ensureGroupKey(group, currentUserId);
        if (!groupKey) {
          throw new Error('GROUP_KEY_UNAVAILABLE');
        }

        setUploadStatus(`Encrypting image ${file.name}...`);
        const plaintextBytes = await file.arrayBuffer();
        const encryptedFile = await encryptBytesWithGroupKey(groupKey, plaintextBytes);

        setUploadStatus(`Uploading image ${file.name}...`);
        const uploadResponse = await mediaApi.uploadEncryptedGroupMedia(
          conversationId,
          encryptedFile.ciphertext,
          encryptedFile.nonce,
          group.keyVersion,
          file.type,
          file.name
        );
        mediaId = uploadResponse.mediaId;
        mimeType = uploadResponse.mimeType;

        let captionCiphertext: string | undefined;
        let captionNonce: string | undefined;
        if (caption) {
          const encryptedCaption = await encryptWithGroupKey(groupKey, caption);
          captionCiphertext = encryptedCaption.ciphertext;
          captionNonce = encryptedCaption.nonce;
        }

        await messageApi.sendMessage({
          conversationId,
          messageType: 'IMAGE',
          mediaId,
          encryptionAlgorithm: 'AES-256-GCM',
          ciphertext: captionCiphertext,
          nonce: captionNonce,
          groupKeyVersion: group.keyVersion,
          replyToMessageId: replyToId,
        });
      } else {
        const uploadResponse = await mediaApi.uploadImage(conversationId, file);
        mediaId = uploadResponse.mediaId;
        mimeType = uploadResponse.mimeType;
        await messageApi.sendMessage({
          conversationId,
          messageType: 'IMAGE',
          mediaId,
          caption,
          replyToMessageId: replyToId,
        });
      }

      onOptimisticImageMessage(mediaId, caption, localPreviewUrl, mimeType, replyToId);
      onMessageSent?.();
      setText('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
      onCancelReply?.();
    } catch (err: unknown) {
      URL.revokeObjectURL(localPreviewUrl);
      console.error('[ConnectX] Image message failure:', err);
      if (err instanceof ApiRequestError && err.code === 'GROUP_KEY_VERSION_MISMATCH' && group) {
        groupKeyManager.invalidate(group.id);
      }
      const message = friendlySendErrorMessage(err);
      alert(`Failed to send image ${file.name}: ` + message);
      throw err instanceof Error ? err : new Error(message);
    } finally {
      setUploadStatus(null);
      setUploading(false);
    }
  };

  const sendDocumentFile = async (file: File) => {
    if (file.size > 50 * 1024 * 1024) {
      alert(`File "${file.name}" must be 50 MB or smaller.`);
      throw new Error('File too large');
    }

    const filename = file.name;
    const replyToId = replyTarget?.messageId;
    setUploading(true);
    setUploadStatus(`Uploading document ${filename}...`);

    try {
      let mediaId: number;
      let mimeType: string;
      let fileSizeBytes: number;

      if (isGroup) {
        if (!group) {
          throw new Error('GROUP_INFO_UNAVAILABLE');
        }
        const groupKey = await groupKeyManager.ensureGroupKey(group, currentUserId);
        if (!groupKey) {
          throw new Error('GROUP_KEY_UNAVAILABLE');
        }

        setUploadStatus(`Encrypting file ${filename}...`);
        const plaintextBytes = await file.arrayBuffer();
        const encryptedFile = await encryptBytesWithGroupKey(groupKey, plaintextBytes);

        setUploadStatus(`Uploading document ${filename}...`);
        const uploadResponse = await mediaApi.uploadEncryptedGroupMedia(
          conversationId,
          encryptedFile.ciphertext,
          encryptedFile.nonce,
          group.keyVersion,
          file.type || 'application/octet-stream',
          filename
        );
        mediaId = uploadResponse.mediaId;
        mimeType = uploadResponse.mimeType;
        fileSizeBytes = uploadResponse.fileSizeBytes;

        // Filename rides in the same field an image caption would -- see sendImageFile's identical
        // encrypt-the-caption step.
        const encryptedName = await encryptWithGroupKey(groupKey, filename);
        await messageApi.sendMessage({
          conversationId,
          messageType: 'DOCUMENT',
          mediaId,
          encryptionAlgorithm: 'AES-256-GCM',
          ciphertext: encryptedName.ciphertext,
          nonce: encryptedName.nonce,
          groupKeyVersion: group.keyVersion,
          replyToMessageId: replyToId,
        });
      } else {
        const uploadResponse = await mediaApi.uploadMediaFile(conversationId, file);
        mediaId = uploadResponse.mediaId;
        mimeType = uploadResponse.mimeType;
        fileSizeBytes = uploadResponse.fileSizeBytes;
        await messageApi.sendMessage({
          conversationId,
          messageType: 'DOCUMENT',
          mediaId,
          caption: filename,
          replyToMessageId: replyToId,
        });
      }

      onOptimisticDocumentMessage(mediaId, filename, mimeType, fileSizeBytes, replyToId);
      onMessageSent?.();
      setText('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
      onCancelReply?.();
    } catch (err: unknown) {
      console.error('[ConnectX] Document message failure:', err);
      if (err instanceof ApiRequestError && err.code === 'GROUP_KEY_VERSION_MISMATCH' && group) {
        groupKeyManager.invalidate(group.id);
      }
      const message = friendlySendErrorMessage(err);
      alert(`Failed to send file ${filename}: ` + message);
      throw err instanceof Error ? err : new Error(message);
    } finally {
      setUploadStatus(null);
      setUploading(false);
    }
  };

  const handleSendBatch = async (images: File[], docs: File[]) => {
    if (sending || uploading) return;

    setUploading(true);
    try {
      for (let i = 0; i < images.length; i++) {
        setUploadStatus(`Sending image ${i + 1} of ${images.length}...`);
        await sendImageFile(images[i]);
      }
      for (let i = 0; i < docs.length; i++) {
        setUploadStatus(`Sending document ${i + 1} of ${docs.length}...`);
        await sendDocumentFile(docs[i]);
      }
    } finally {
      setUploadStatus(null);
      setUploading(false);
    }
  };

  const handleSubmitEdit = async () => {
    const content = text.trim();
    if (!content || submittingEdit || !onSubmitEdit) return;
    setSubmittingEdit(true);
    try {
      await onSubmitEdit(content);
      setText('');
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to edit message';
      alert(message);
    } finally {
      setSubmittingEdit(false);
      textareaRef.current?.focus();
    }
  };

  const handleSendText = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();

    if (editTarget) {
      await handleSubmitEdit();
      return;
    }

    const content = text.trim();
    if (!content || isSendingRef.current || uploading) return;

    const replyToId = replyTarget?.messageId;
    const clientTempId =
      typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    isSendingRef.current = true;
    setSending(true);
    stopTyping();

    // Clear composer text immediately and maintain focus for continuous fast typing
    setText('');
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.focus();
    }
    onCancelReply?.();

    // 1. Immediately append to UI optimistically
    onOptimisticMessage(content, '', '', 0, replyToId, clientTempId);

    try {
      if (isGroup) {
        if (!group) {
          throw new Error('GROUP_INFO_UNAVAILABLE');
        }
        const groupKey = await groupKeyManager.ensureGroupKey(group, currentUserId);
        if (!groupKey) {
          throw new Error('GROUP_KEY_UNAVAILABLE');
        }
        const encrypted = await encryptWithGroupKey(groupKey, content);
        await messageApi.sendMessage({
          conversationId,
          messageType: 'TEXT',
          encryptionAlgorithm: 'AES-256-GCM',
          ciphertext: encrypted.ciphertext,
          nonce: encrypted.nonce,
          groupKeyVersion: group.keyVersion,
          replyToMessageId: replyToId,
          requestId: clientTempId,
        });
        onMessageSent?.();
      } else {
        if (!recipientUserId) {
          throw new Error('RECIPIENT_UNAVAILABLE');
        }
        let recipientPublicKeys = conversationCache.getPublicKeys(recipientUserId);
        if (!recipientPublicKeys || recipientPublicKeys.length === 0) {
          recipientPublicKeys = await deviceApi.getUserPublicKeys(recipientUserId);
          if (recipientPublicKeys && recipientPublicKeys.length > 0) {
            conversationCache.setPublicKeys(recipientUserId, recipientPublicKeys);
          }
        }
        if (!recipientPublicKeys || recipientPublicKeys.length === 0) {
          throw new Error('Recipient has no registered public keys on the server.');
        }
        const recipientDevice = recipientPublicKeys[0];
        const recipientPublicKeyBase64 = recipientDevice.publicKey;

        const senderPrivateKey = await keyManager.getPrivateKey(currentUserId);
        if (!senderPrivateKey) {
          throw new Error('Sender private key is missing from local browser vault.');
        }
        const senderDevice = await keyManager.getLocalDevice(currentUserId);
        if (!senderDevice) {
          throw new Error('Sender device metadata is missing. Please sign out and sign in again.');
        }

        const encrypted = await encryptMessage(senderPrivateKey, recipientPublicKeyBase64, content);

        const sendPayload = {
          conversationId,
          messageType: 'TEXT' as const,
          senderDeviceId: senderDevice.deviceId,
          recipientDeviceId: recipientDevice.deviceId,
          encryptionAlgorithm: 'ECDH-P256+AES-256-GCM',
          ciphertext: encrypted.ciphertext,
          nonce: encrypted.nonce,
          replyToMessageId: replyToId,
          requestId: clientTempId,
        };

        await messageApi.sendMessage(sendPayload);
        onMessageSent?.();
      }
    } catch (err: unknown) {
      console.error('[ConnectX E2EE] Message transmission failure:', err);
      // The optimistic bubble inserted above must not linger looking "sent" once the backend has
      // actually rejected it (e.g. NOT_CONNECTED) -- remove it before surfacing the error.
      onOptimisticMessageFailed?.(clientTempId);
      if (err instanceof ApiRequestError && err.code === 'GROUP_KEY_VERSION_MISMATCH' && group) {
        // This client's cached key fell behind a rotation mid-flight -- drop it so the next
        // attempt re-fetches/re-derives the current one instead of retrying with the same stale
        // key indefinitely.
        groupKeyManager.invalidate(group.id);
      }
      alert(friendlySendErrorMessage(err));
      // Restore unsent text on failure
      setText(content);
    } finally {
      isSendingRef.current = false;
      setSending(false);
      // Ensure focus remains intact after async completion
      textareaRef.current?.focus();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      // Enter without shift sends message
      e.preventDefault();
      handleSendText();
    }
  };

  const handleTextChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setText(e.target.value);
    const textarea = e.target;
    textarea.style.height = 'auto';
    const nextHeight = Math.min(textarea.scrollHeight, 140);
    textarea.style.height = `${nextHeight}px`;

    if (e.target.value.trim()) {
      notifyTyping();
    } else {
      stopTyping();
    }
  };

  const handlePaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    if (!e.clipboardData) return;

    const items = Array.from(e.clipboardData.items);
    const imageItem = items.find((item) => item.type.startsWith('image/'));

    if (imageItem) {
      e.preventDefault();
      const file = imageItem.getAsFile();
      if (file) {
        setPendingImages([file]);
        setPendingDocs([]);
        setShowBatchModal(true);
      }
    }
  };

  const handleImageSelected = (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = Array.from(event.target.files || []);
    event.target.value = '';
    if (selectedFiles.length === 0) return;

    if (selectedFiles.length > 10) {
      alert('Maximum 10 images can be sent at once.');
      return;
    }

    setPendingImages(selectedFiles);
    setPendingDocs([]);
    setShowBatchModal(true);
  };

  const handleDocSelected = (event: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = Array.from(event.target.files || []);
    event.target.value = '';
    if (selectedFiles.length === 0) return;

    if (selectedFiles.length > 5) {
      alert('Maximum 5 documents can be sent at once.');
      return;
    }

    setPendingDocs(selectedFiles);
    setPendingImages([]);
    setShowBatchModal(true);
  };

  const handleShareLocation = async () => {
    if (busy) return;

    closeMediaMenu();
    setSharingLocation(true);
    setUploadStatus('Getting location...');
    const replyToId = replyTarget?.messageId;
    const clientTempId =
      typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    try {
      const location = await resolveCurrentLocation();
      setUploadStatus('Sending location...');

      await messageApi.sendMessage({
        conversationId,
        messageType: 'LOCATION',
        latitude: location.latitude,
        longitude: location.longitude,
        locationLabel: location.locationLabel,
        replyToMessageId: replyToId,
        requestId: clientTempId,
      });

      onOptimisticLocationMessage(
        location.latitude,
        location.longitude,
        location.locationLabel,
        replyToId,
        clientTempId
      );
      onMessageSent?.();
      onCancelReply?.();
    } catch (err: unknown) {
      console.error('[ConnectX] Location message failure:', err);
      alert(geolocationErrorMessage(err));
    } finally {
      setUploadStatus(null);
      setSharingLocation(false);
    }
  };

  const handleOpenCamera = () => {
    closeMediaMenu();
    setShowCamera(true);
  };

  const handleOpenImagePicker = () => {
    closeMediaMenu();
    fileInputRef.current?.click();
  };

  const handleOpenDocPicker = () => {
    closeMediaMenu();
    docInputRef.current?.click();
  };

  const handleCameraCaptureSend = async (file: File) => {
    await sendImageFile(file);
  };

  const busy = uploading || sharingLocation;

  return (
    <>
      <div className="relative w-full px-2.5 sm:px-4 md:px-6 py-2 sm:py-3 bg-white/95 dark:bg-[#0a0e1a]/95 backdrop-blur-sm border-t border-slate-200/90 dark:border-slate-800/80 select-none flex-shrink-0 z-20">
        {/* Reply Preview Bar */}
        {replyTarget && (
          <div className="w-full mb-2 flex items-center justify-between gap-3 p-2.5 bg-violet-500/10 dark:bg-violet-950/40 border-l-4 border-violet-600 dark:border-violet-500 rounded-r-xl text-xs animate-pop-in">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5 font-bold text-violet-600 dark:text-violet-400">
                <CornerUpLeft className="w-3.5 h-3.5" />
                <span>Replying to {replyTarget.senderUsername}</span>
              </div>
              <p className="text-slate-600 dark:text-slate-300 truncate mt-0.5">
                {replyTarget.previewText}
              </p>
            </div>
            <button
              type="button"
              onClick={onCancelReply}
              className="p-1 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-800 transition-colors cursor-pointer"
              aria-label="Cancel reply"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        )}

        {/* Edit Preview Bar */}
        {editTarget && (
          <div className="w-full mb-2 flex items-center justify-between gap-3 p-2.5 bg-amber-500/10 dark:bg-amber-950/30 border-l-4 border-amber-500 rounded-r-xl text-xs animate-pop-in">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5 font-bold text-amber-600 dark:text-amber-400">
                <Pencil className="w-3.5 h-3.5" />
                <span>Editing message</span>
              </div>
            </div>
            <button
              type="button"
              onClick={onCancelEdit}
              className="p-1 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-800 transition-colors cursor-pointer"
              aria-label="Cancel edit"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        )}

        {uploadStatus && (
          <div className="w-full mb-2">
            <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-violet-500/10 text-violet-600 dark:text-violet-400 text-xs font-medium">
              {uploading || sharingLocation ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <ImagePlus className="w-3.5 h-3.5" />
              )}
              {uploadStatus}
            </div>
          </div>
        )}

        <form
          onSubmit={handleSendText}
          className="w-full flex items-center gap-1.5 sm:gap-2 min-w-0"
        >
          <input
            ref={fileInputRef}
            type="file"
            accept={MEDIA_IMAGE_ACCEPT}
            multiple
            className="hidden"
            onChange={handleImageSelected}
          />
          <input
            ref={docInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={handleDocSelected}
          />

          <div className="relative flex-shrink-0 flex items-center justify-center">
            <button
              type="button"
              onClick={() => {
                if (isGroup) {
                  setShowGroupMediaMilestoneModal(true);
                } else {
                  setShowMediaMenu((open) => !open);
                }
              }}
              disabled={busy || !!editTarget}
              className="w-9 h-9 sm:w-10 sm:h-10 flex items-center justify-center text-slate-500 dark:text-slate-400 hover:text-violet-600 dark:hover:text-violet-400 rounded-full hover:bg-slate-100 dark:hover:bg-slate-800/60 active:scale-95 transition-all disabled:opacity-50 cursor-pointer"
              aria-label="Open media options"
              aria-expanded={isGroup ? showGroupMediaMilestoneModal : showMediaMenu}
              aria-haspopup={isGroup ? 'dialog' : 'menu'}
            >
              {uploading || sharingLocation ? (
                <Loader2 className="w-5 h-5 animate-spin" />
              ) : (
                <Paperclip className="w-5 h-5" />
              )}
            </button>

            {showMediaMenu && !editTarget && !isGroup && (
              <>
                <div className="fixed inset-0 z-40" onClick={closeMediaMenu} aria-hidden="true" />
                <div
                  role="menu"
                  className="absolute bottom-full left-0 mb-2 z-50 w-52 max-w-[calc(100vw-2rem)] bg-white/95 dark:bg-[#0c101c]/95 border border-slate-200/90 dark:border-slate-800/90 rounded-xl shadow-xl p-1.5 text-xs sm:text-sm backdrop-blur-sm animate-pop-in select-none"
                >
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenCamera}
                    disabled={busy}
                    className="w-full text-left px-3 py-2 hover:bg-slate-100 dark:hover:bg-slate-800/70 rounded-lg flex items-center gap-3 text-slate-800 dark:text-slate-100 disabled:opacity-50 cursor-pointer transition-colors"
                  >
                    <span className="flex h-8 w-8 items-center justify-center rounded-full bg-violet-500/15 text-violet-600 dark:text-violet-300 flex-shrink-0">
                      <Camera className="w-4 h-4" />
                    </span>
                    <span className="min-w-0">
                      <span className="block font-medium">Camera</span>
                      <span className="block text-[10px] sm:text-[11px] text-slate-400">Take a photo</span>
                    </span>
                  </button>

                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenImagePicker}
                    disabled={busy}
                    className="w-full text-left px-3 py-2 hover:bg-slate-100 dark:hover:bg-slate-800/70 rounded-lg flex items-center gap-3 text-slate-800 dark:text-slate-100 disabled:opacity-50 cursor-pointer transition-colors"
                  >
                    <span className="flex h-8 w-8 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-300 flex-shrink-0">
                      <ImageIcon className="w-4 h-4" />
                    </span>
                    <span className="min-w-0">
                      <span className="block font-medium">Image</span>
                      <span className="block text-[10px] sm:text-[11px] text-slate-400">Choose images (max 10)</span>
                    </span>
                  </button>

                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenDocPicker}
                    disabled={busy}
                    className="w-full text-left px-3 py-2 hover:bg-slate-100 dark:hover:bg-slate-800/70 rounded-lg flex items-center gap-3 text-slate-800 dark:text-slate-100 disabled:opacity-50 cursor-pointer transition-colors"
                  >
                    <span className="flex h-8 w-8 items-center justify-center rounded-full bg-blue-500/15 text-blue-600 dark:text-blue-300 flex-shrink-0">
                      <FileText className="w-4 h-4" />
                    </span>
                    <span className="min-w-0">
                      <span className="block font-medium">Document</span>
                      <span className="block text-[10px] sm:text-[11px] text-slate-400">Share files (max 5)</span>
                    </span>
                  </button>

                  {!isGroup && (
                    <button
                      type="button"
                      role="menuitem"
                      onClick={handleShareLocation}
                      disabled={busy}
                      className="w-full text-left px-3 py-2 hover:bg-slate-100 dark:hover:bg-slate-800/70 rounded-lg flex items-center gap-3 text-slate-800 dark:text-slate-100 disabled:opacity-50 cursor-pointer transition-colors"
                    >
                      <span className="flex h-8 w-8 items-center justify-center rounded-full bg-rose-500/15 text-rose-600 dark:text-rose-300 flex-shrink-0">
                        <MapPin className="w-4 h-4" />
                      </span>
                      <span className="min-w-0">
                        <span className="block font-medium">Location</span>
                        <span className="block text-[10px] sm:text-[11px] text-slate-400">Share current place</span>
                      </span>
                    </button>
                  )}
                </div>
              </>
            )}
          </div>

          <div className="relative flex-1 min-w-0 flex items-center">
            <textarea
              ref={textareaRef}
              rows={1}
              value={text}
              onChange={handleTextChange}
              onKeyDown={handleKeyDown}
              onPaste={handlePaste}
              placeholder={
                uploading
                  ? 'Uploading media...'
                  : sharingLocation
                  ? 'Sharing location...'
                  : editTarget
                  ? 'Edit message...'
                  : replyTarget
                  ? `Reply to @${replyTarget.senderUsername}...`
                  : 'Type a message...'
              }
              className="w-full pl-3.5 sm:pl-4 pr-8 sm:pr-9 py-2.5 sm:py-3 bg-slate-100/90 dark:bg-slate-900/80 border border-slate-200/90 dark:border-slate-800/80 rounded-2xl text-sm sm:text-[15px] text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500/60 focus:ring-1 focus:ring-violet-500/30 transition-all resize-none max-h-32 sm:max-h-36 overflow-y-auto leading-relaxed select-text block"
            />
            <Lock className="w-3.5 h-3.5 absolute right-3.5 top-1/2 -translate-y-1/2 text-violet-500/60 dark:text-violet-400/50 pointer-events-none" />
          </div>

          <div className="flex-shrink-0 flex items-center justify-center">
            {text.trim() ? (
              <button
                type="submit"
                disabled={busy || submittingEdit}
                className="w-9 h-9 sm:w-10 sm:h-10 bg-violet-600 hover:bg-violet-500 active:scale-95 disabled:opacity-60 text-white rounded-full shadow-sm hover:shadow-md hover:shadow-violet-600/25 transition-all flex items-center justify-center cursor-pointer"
                aria-label={editTarget ? 'Save edit' : 'Send message'}
              >
                {sending || submittingEdit ? (
                  <Loader2 className="w-5 h-5 animate-spin" />
                ) : editTarget ? (
                  <Check className="w-5 h-5" />
                ) : (
                  <Send className="w-5 h-5" />
                )}
              </button>
            ) : (
              <button
                type="button"
                onClick={() => alert('Voice messages are scheduled for a future milestone.')}
                disabled={!!editTarget}
                className="w-9 h-9 sm:w-10 sm:h-10 text-slate-500 dark:text-slate-400 hover:text-violet-600 dark:hover:text-violet-400 rounded-full hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40 flex items-center justify-center cursor-pointer"
                aria-label="Record voice message"
              >
                <Mic className="w-5 h-5" />
              </button>
            )}
          </div>
        </form>
      </div>

      <CameraCaptureModal
        open={showCamera}
        busy={uploading}
        onClose={() => setShowCamera(false)}
        onCaptureSend={handleCameraCaptureSend}
      />

      {showBatchModal && (
        <MediaBatchPreviewModal
          initialImageFiles={pendingImages}
          initialDocFiles={pendingDocs}
          onClose={() => setShowBatchModal(false)}
          onSendBatch={handleSendBatch}
        />
      )}

      {/* Group Media Future Milestone Modal */}
      {showGroupMediaMilestoneModal && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in select-none"
          onClick={() => setShowGroupMediaMilestoneModal(false)}
        >
          <div
            className="w-full max-w-sm bg-white dark:bg-[#0c101c] border border-slate-200/90 dark:border-slate-800/90 rounded-2xl shadow-2xl p-6 text-center animate-pop-in relative overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Subtle violet top glow */}
            <div className="absolute -top-12 left-1/2 -translate-x-1/2 w-36 h-36 bg-violet-500/10 dark:bg-violet-500/20 rounded-full blur-2xl pointer-events-none" />

            <div className="relative z-10 flex flex-col items-center">
              <div className="w-14 h-14 rounded-2xl bg-violet-500/10 dark:bg-violet-500/15 border border-violet-500/20 text-violet-600 dark:text-violet-400 flex items-center justify-center mb-4 shadow-sm">
                <ImagePlus className="w-6 h-6" />
              </div>

              <h3 className="text-lg font-bold text-slate-900 dark:text-white tracking-tight">
                Group Media
              </h3>

              <p className="text-xs sm:text-sm text-slate-600 dark:text-slate-300 mt-2 leading-relaxed max-w-xs">
                Group media sharing is planned for a future ConnectX milestone.
              </p>

              <button
                type="button"
                onClick={() => setShowGroupMediaMilestoneModal(false)}
                className="mt-6 w-full py-2.5 px-4 rounded-xl bg-violet-600 hover:bg-violet-500 active:scale-[0.98] text-white text-xs sm:text-sm font-semibold shadow-md shadow-violet-600/20 transition-all cursor-pointer"
              >
                Got it
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
