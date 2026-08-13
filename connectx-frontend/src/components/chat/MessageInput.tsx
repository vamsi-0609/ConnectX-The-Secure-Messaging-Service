import React, { useRef, useState } from 'react';
import { Send, Lock, Paperclip, Mic, Loader2, ImagePlus, Camera, MapPin, Image as ImageIcon, FileText } from 'lucide-react';
import { encryptMessage } from '../../crypto/encryption';
import { keyManager } from '../../crypto/keyManager';
import { deviceApi } from '../../api/deviceApi';
import { messageApi } from '../../api/messageApi';
import { mediaApi } from '../../api/mediaApi';
import { MEDIA_IMAGE_ACCEPT, validateMediaImageFile } from '../../utils/mediaImage';
import { geolocationErrorMessage, resolveCurrentLocation } from '../../utils/location';
import { CameraCaptureModal } from './CameraCaptureModal';

interface MessageInputProps {
  conversationId: number;
  recipientUserId: number;
  currentUserId: number;
  onOptimisticMessage: (plaintext: string, ciphertext: string, nonce: string, recipientDeviceId: number) => void;
  onOptimisticImageMessage: (mediaId: number, caption: string | undefined, localPreviewUrl: string, mimeType: string) => void;
  onOptimisticLocationMessage: (
    latitude: number,
    longitude: number,
    locationLabel: string | undefined
  ) => void;
  onOptimisticDocumentMessage: (
    mediaId: number,
    filename: string,
    mimeType: string,
    fileSizeBytes: number
  ) => void;
  onMessageSent?: () => void;
}

export const MessageInput: React.FC<MessageInputProps> = ({
  conversationId,
  recipientUserId,
  currentUserId,
  onOptimisticMessage,
  onOptimisticImageMessage,
  onOptimisticLocationMessage,
  onOptimisticDocumentMessage,
  onMessageSent,
}) => {
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState<string | null>(null);
  const [sharingLocation, setSharingLocation] = useState(false);
  const [showCamera, setShowCamera] = useState(false);
  const [showMediaMenu, setShowMediaMenu] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const docInputRef = useRef<HTMLInputElement>(null);

  const closeMediaMenu = () => setShowMediaMenu(false);

  const sendImageFile = async (file: File) => {
    if (sending || uploading) {
      throw new Error('Another message is still sending.');
    }

    const validationError = validateMediaImageFile(file);
    if (validationError) {
      alert(validationError);
      throw new Error(validationError);
    }

    const caption = text.trim() || undefined;
    const localPreviewUrl = URL.createObjectURL(file);

    setUploading(true);
    setUploadStatus('Uploading...');

    try {
      const uploadResponse = await mediaApi.uploadImage(conversationId, file);
      setUploadStatus('Upload complete');

      await messageApi.sendMessage({
        conversationId,
        messageType: 'IMAGE',
        mediaId: uploadResponse.mediaId,
        caption,
      });

      onOptimisticImageMessage(uploadResponse.mediaId, caption, localPreviewUrl, uploadResponse.mimeType);
      onMessageSent?.();
      setText('');
    } catch (err: unknown) {
      URL.revokeObjectURL(localPreviewUrl);
      console.error('[ConnectX] Image message failure:', err);
      const message = err instanceof Error ? err.message : 'Unknown error';
      alert('Failed to send image: ' + message);
      throw err instanceof Error ? err : new Error(message);
    } finally {
      setUploadStatus(null);
      setUploading(false);
    }
  };

  const sendDocumentFile = async (file: File) => {
    if (sending || uploading) {
      throw new Error('Another message is still sending.');
    }

    if (file.size > 50 * 1024 * 1024) {
      alert('File must be 50 MB or smaller.');
      throw new Error('File too large');
    }

    const filename = file.name;
    setUploading(true);
    setUploadStatus('Uploading file...');

    try {
      const uploadResponse = await mediaApi.uploadMediaFile(conversationId, file);
      setUploadStatus('Upload complete');

      await messageApi.sendMessage({
        conversationId,
        messageType: 'DOCUMENT',
        mediaId: uploadResponse.mediaId,
        caption: filename,
      });

      onOptimisticDocumentMessage(
        uploadResponse.mediaId,
        filename,
        uploadResponse.mimeType,
        uploadResponse.fileSizeBytes
      );
      onMessageSent?.();
      setText('');
    } catch (err: unknown) {
      console.error('[ConnectX] Document message failure:', err);
      const message = err instanceof Error ? err.message : 'Unknown error';
      alert('Failed to send file: ' + message);
      throw err instanceof Error ? err : new Error(message);
    } finally {
      setUploadStatus(null);
      setUploading(false);
    }
  };

  const handleSendText = async (e: React.FormEvent) => {
    e.preventDefault();
    const content = text.trim();
    if (!content || sending || uploading) return;

    setSending(true);

    try {
      const recipientPublicKeys = await deviceApi.getUserPublicKeys(recipientUserId);
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
      };

      await messageApi.sendMessage(sendPayload);

      onOptimisticMessage(content, encrypted.ciphertext, encrypted.nonce, recipientDevice.deviceId);
      onMessageSent?.();
      setText('');
    } catch (err: unknown) {
      console.error('[ConnectX E2EE] Message transmission failure:', err);
      const message = err instanceof Error ? err.message : 'Unknown error';
      alert('Failed to send message: ' + message);
    } finally {
      setSending(false);
    }
  };

  const handleImageSelected = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    try {
      await sendImageFile(file);
    } catch {
      // Error already surfaced to the user.
    }
  };

  const handleDocSelected = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    try {
      await sendDocumentFile(file);
    } catch {
      // Error already surfaced to the user.
    }
  };

  const handleShareLocation = async () => {
    if (busy) return;

    closeMediaMenu();
    setSharingLocation(true);
    setUploadStatus('Getting location...');

    try {
      const location = await resolveCurrentLocation();
      setUploadStatus('Sending location...');

      await messageApi.sendMessage({
        conversationId,
        messageType: 'LOCATION',
        latitude: location.latitude,
        longitude: location.longitude,
        locationLabel: location.locationLabel,
      });

      onOptimisticLocationMessage(location.latitude, location.longitude, location.locationLabel);
      onMessageSent?.();
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

  const busy = sending || uploading || sharingLocation;

  return (
    <>
      <div className="flex-shrink-0 border-t border-slate-200/80 dark:border-slate-800/80 bg-white dark:bg-[#0f172a] px-3 py-2 md:min-h-[68px] md:py-3 md:px-8 lg:px-12 xl:px-16">
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

        <form onSubmit={handleSendText} className="max-w-3xl mx-auto flex items-center gap-1.5 md:gap-2 md:max-w-none md:mx-0">
          <input
            ref={fileInputRef}
            type="file"
            accept={MEDIA_IMAGE_ACCEPT}
            className="hidden"
            onChange={handleImageSelected}
          />
          <input
            ref={docInputRef}
            type="file"
            className="hidden"
            onChange={handleDocSelected}
          />

          <div className="relative flex-shrink-0">
            <button
              type="button"
              onClick={() => setShowMediaMenu((open) => !open)}
              disabled={busy}
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

            {showMediaMenu && (
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
                      <span className="block text-[11px] text-slate-400">Choose from gallery</span>
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
                      <span className="block text-[11px] text-slate-400">Share any file</span>
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
            <input
              type="text"
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={uploading ? 'Uploading image...' : sharingLocation ? 'Sharing location...' : 'Type a message or add a caption...'}
              className="w-full pl-4 md:pl-5 pr-10 md:pr-12 py-2.5 md:h-12 md:py-3 bg-slate-100 dark:bg-slate-900/90 border border-slate-200 dark:border-slate-700/80 rounded-full text-sm md:text-[15px] text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500/60 focus:ring-1 focus:ring-indigo-500/30 transition-all"
              disabled={busy}
            />
            <Lock className="w-3.5 h-3.5 md:w-4 md:h-4 absolute right-3.5 md:right-4 top-1/2 -translate-y-1/2 text-pink-400/70 pointer-events-none" />
          </div>

          {text.trim() ? (
            <button
              type="submit"
              disabled={busy}
              className="p-2.5 md:p-3 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 text-white rounded-full shadow-md shadow-indigo-600/25 transition-all flex-shrink-0"
              aria-label="Send message"
            >
              {sending ? <Loader2 className="w-5 h-5 md:w-[22px] md:h-[22px] animate-spin" /> : <Send className="w-5 h-5 md:w-[22px] md:h-[22px]" />}
            </button>
          ) : (
            <button
              type="button"
              onClick={() => alert('Voice messages are scheduled for a future milestone.')}
              className="p-2.5 md:p-3 text-slate-500 dark:text-slate-400 hover:text-indigo-500 dark:hover:text-indigo-300 rounded-full hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors flex-shrink-0"
              aria-label="Record voice message"
            >
              <Mic className="w-5 h-5 md:w-[22px] md:h-[22px]" />
            </button>
          )}
        </form>
      </div>

      <CameraCaptureModal
        open={showCamera}
        busy={uploading}
        onClose={() => setShowCamera(false)}
        onCaptureSend={handleCameraCaptureSend}
      />
    </>
  );
};
