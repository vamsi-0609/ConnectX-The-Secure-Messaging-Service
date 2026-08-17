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
import { keyManager } from '../../crypto/keyManager';
import { deviceApi } from '../../api/deviceApi';
import { messageApi } from '../../api/messageApi';
import { mediaApi } from '../../api/mediaApi';
import { MEDIA_IMAGE_ACCEPT, validateMediaImageFile } from '../../utils/mediaImage';
import { geolocationErrorMessage, resolveCurrentLocation } from '../../utils/location';
import { CameraCaptureModal } from './CameraCaptureModal';
import { MediaBatchPreviewModal } from './MediaBatchPreviewModal';
import { ReplyTarget, Message } from '../../types';
import { conversationCache } from '../../cache/conversationCache';
import { wsClient } from '../../websocket/WebSocketClient';
import { activityGuard } from '../../utils/activityGuard';
import { ApiRequestError } from '../../api/apiClient';

const TYPING_IDLE_MS = 3000;

interface MessageInputProps {
  conversationId: number;
  recipientUserId: number;
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
      setPendingImages(images);
      setPendingDocs(docs);
      setShowBatchModal(true);
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
      const uploadResponse = await mediaApi.uploadImage(conversationId, file);
      await messageApi.sendMessage({
        conversationId,
        messageType: 'IMAGE',
        mediaId: uploadResponse.mediaId,
        caption,
        replyToMessageId: replyToId,
      });

      onOptimisticImageMessage(
        uploadResponse.mediaId,
        caption,
        localPreviewUrl,
        uploadResponse.mimeType,
        replyToId
      );
      onMessageSent?.();
      setText('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
      onCancelReply?.();
    } catch (err: unknown) {
      URL.revokeObjectURL(localPreviewUrl);
      console.error('[ConnectX] Image message failure:', err);
      const message = err instanceof Error ? err.message : 'Unknown error';
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
      const uploadResponse = await mediaApi.uploadMediaFile(conversationId, file);
      await messageApi.sendMessage({
        conversationId,
        messageType: 'DOCUMENT',
        mediaId: uploadResponse.mediaId,
        caption: filename,
        replyToMessageId: replyToId,
      });

      onOptimisticDocumentMessage(
        uploadResponse.mediaId,
        filename,
        uploadResponse.mimeType,
        uploadResponse.fileSizeBytes,
        replyToId
      );
      onMessageSent?.();
      setText('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
      onCancelReply?.();
    } catch (err: unknown) {
      console.error('[ConnectX] Document message failure:', err);
      const message = err instanceof Error ? err.message : 'Unknown error';
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
    } catch (err: unknown) {
      console.error('[ConnectX E2EE] Message transmission failure:', err);
      // The optimistic bubble inserted above must not linger looking "sent" once the backend has
      // actually rejected it (e.g. NOT_CONNECTED) -- remove it before surfacing the error.
      onOptimisticMessageFailed?.(clientTempId);
      let message = err instanceof Error ? err.message : 'Unknown error';
      if (
        message.includes('NO_ACTIVE_CRYPTO_DEVICE') ||
        message.includes('active cryptographic devices') ||
        message.includes("hasn't activated secure messaging")
      ) {
        message = "This user hasn't activated secure messaging yet.";
      } else if (err instanceof ApiRequestError && err.code === 'NOT_CONNECTED') {
        message = "You're no longer connected with this user. Send a new connection request to message them again.";
      }
      alert(message);
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
      <div className="flex-shrink-0 border-t border-slate-200/80 dark:border-slate-800/80 bg-white dark:bg-[#0f172a] px-3 py-2 md:py-3 md:px-8 lg:px-12 xl:px-16 transition-all select-none">
        {/* Reply Preview Bar */}
        {replyTarget && (
          <div className="max-w-3xl mx-auto mb-2 md:max-w-none md:mx-0 flex items-center justify-between gap-3 p-2.5 bg-indigo-500/10 dark:bg-indigo-950/40 border-l-4 border-indigo-500 rounded-r-xl text-xs animate-pop-in">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5 font-bold text-indigo-600 dark:text-indigo-400">
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
              className="p-1 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-800 transition-colors"
              aria-label="Cancel reply"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        )}

        {/* Edit Preview Bar */}
        {editTarget && (
          <div className="max-w-3xl mx-auto mb-2 md:max-w-none md:mx-0 flex items-center justify-between gap-3 p-2.5 bg-amber-500/10 dark:bg-amber-950/30 border-l-4 border-amber-500 rounded-r-xl text-xs animate-pop-in">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5 font-bold text-amber-600 dark:text-amber-400">
                <Pencil className="w-3.5 h-3.5" />
                <span>Editing message</span>
              </div>
            </div>
            <button
              type="button"
              onClick={onCancelEdit}
              className="p-1 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-200/50 dark:hover:bg-slate-800 transition-colors"
              aria-label="Cancel edit"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        )}

        {uploadStatus && (
          <div className="max-w-3xl mx-auto mb-2 md:max-w-none md:mx-0">
            <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-indigo-500/10 text-indigo-500 text-xs font-medium">
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
          className="max-w-3xl mx-auto flex items-end gap-1.5 md:gap-2 md:max-w-none md:mx-0"
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

          <div className="relative flex-shrink-0 mb-1">
            <button
              type="button"
              onClick={() => setShowMediaMenu((open) => !open)}
              disabled={busy || !!editTarget}
              className="p-2.5 md:p-3 text-slate-500 dark:text-slate-400 hover:text-indigo-500 dark:hover:text-indigo-300 rounded-full hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-50"
              aria-label="Open media options"
              aria-expanded={showMediaMenu}
              aria-haspopup="menu"
            >
              {uploading || sharingLocation ? (
                <Loader2 className="w-5 h-5 md:w-[22px] md:h-[22px] animate-spin" />
              ) : (
                <Paperclip className="w-5 h-5 md:w-[22px] md:h-[22px]" />
              )}
            </button>

            {showMediaMenu && !editTarget && (
              <>
                <div className="fixed inset-0 z-20" onClick={closeMediaMenu} aria-hidden="true" />
                <div
                  role="menu"
                  className="absolute bottom-full left-0 mb-2 z-30 w-52 bg-slate-900 border border-slate-700 rounded-xl shadow-xl p-1.5 text-sm"
                >
                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenCamera}
                    disabled={busy}
                    className="w-full text-left px-3 py-2.5 hover:bg-slate-800 rounded-lg flex items-center gap-3 text-slate-100 disabled:opacity-50"
                  >
                    <span className="flex h-9 w-9 items-center justify-center rounded-full bg-indigo-500/15 text-indigo-300">
                      <Camera className="w-4 h-4" />
                    </span>
                    <span>
                      <span className="block font-medium">Camera</span>
                      <span className="block text-[11px] text-slate-400">Take a photo</span>
                    </span>
                  </button>

                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenImagePicker}
                    disabled={busy}
                    className="w-full text-left px-3 py-2.5 hover:bg-slate-800 rounded-lg flex items-center gap-3 text-slate-100 disabled:opacity-50"
                  >
                    <span className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-300">
                      <ImageIcon className="w-4 h-4" />
                    </span>
                    <span>
                      <span className="block font-medium">Image</span>
                      <span className="block text-[11px] text-slate-400">Choose images (max 10)</span>
                    </span>
                  </button>

                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleOpenDocPicker}
                    disabled={busy}
                    className="w-full text-left px-3 py-2.5 hover:bg-slate-800 rounded-lg flex items-center gap-3 text-slate-100 disabled:opacity-50"
                  >
                    <span className="flex h-9 w-9 items-center justify-center rounded-full bg-blue-500/15 text-blue-300">
                      <FileText className="w-4 h-4" />
                    </span>
                    <span>
                      <span className="block font-medium">Document</span>
                      <span className="block text-[11px] text-slate-400">Share files (max 5)</span>
                    </span>
                  </button>

                  <button
                    type="button"
                    role="menuitem"
                    onClick={handleShareLocation}
                    disabled={busy}
                    className="w-full text-left px-3 py-2.5 hover:bg-slate-800 rounded-lg flex items-center gap-3 text-slate-100 disabled:opacity-50"
                  >
                    <span className="flex h-9 w-9 items-center justify-center rounded-full bg-rose-500/15 text-rose-300">
                      <MapPin className="w-4 h-4" />
                    </span>
                    <span>
                      <span className="block font-medium">Location</span>
                      <span className="block text-[11px] text-slate-400">Share current place</span>
                    </span>
                  </button>
                </div>
              </>
            )}
          </div>

          <div className="relative flex-1 min-w-0">
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
              className="w-full pl-4 md:pl-5 pr-10 md:pr-12 py-2.5 md:py-3 bg-slate-100 dark:bg-slate-900/90 border border-slate-200 dark:border-slate-700/80 rounded-2xl text-sm md:text-[15px] text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500/60 focus:ring-1 focus:ring-indigo-500/30 transition-all resize-none max-h-36 overflow-y-auto leading-relaxed select-text"
            />
            <Lock className="w-3.5 h-3.5 md:w-4 md:h-4 absolute right-3.5 md:right-4 bottom-3.5 text-pink-400/70 pointer-events-none" />
          </div>

          <div className="flex-shrink-0 mb-1">
            {text.trim() ? (
              <button
                type="submit"
                disabled={busy || submittingEdit}
                className="p-2.5 md:p-3 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 text-white rounded-full shadow-md shadow-indigo-600/25 transition-all flex items-center justify-center"
                aria-label={editTarget ? 'Save edit' : 'Send message'}
              >
                {sending || submittingEdit ? (
                  <Loader2 className="w-5 h-5 md:w-[22px] md:h-[22px] animate-spin" />
                ) : editTarget ? (
                  <Check className="w-5 h-5 md:w-[22px] md:h-[22px]" />
                ) : (
                  <Send className="w-5 h-5 md:w-[22px] md:h-[22px]" />
                )}
              </button>
            ) : (
              <button
                type="button"
                onClick={() => alert('Voice messages are scheduled for a future milestone.')}
                disabled={!!editTarget}
                className="p-2.5 md:p-3 text-slate-500 dark:text-slate-400 hover:text-indigo-500 dark:hover:text-indigo-300 rounded-full hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors disabled:opacity-40"
                aria-label="Record voice message"
              >
                <Mic className="w-5 h-5 md:w-[22px] md:h-[22px]" />
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
    </>
  );
};
