import React, { useEffect, useState } from 'react';
import { ShieldCheck, X, ArrowLeft, Laptop, ShieldOff, UserMinus, Loader2 } from 'lucide-react';
import { User, UserPublicKey } from '../../types';
import { deviceApi } from '../../api/deviceApi';
import { UserAvatar } from '../common/UserAvatar';
import { BlockUserConfirmDialog } from './BlockUserConfirmDialog';
import { RemoveConnectionConfirmDialog } from './RemoveConnectionConfirmDialog';

interface ContactInfoDrawerProps {
  recipient: User | null;
  onClose: () => void;
  isBlocked?: boolean;
  onBlock?: (userId: number) => Promise<void>;
  onUnblock?: (userId: number) => Promise<void>;
  isConnected?: boolean;
  onRemoveConnection?: (userId: number) => Promise<void>;
}

export const ContactInfoDrawer: React.FC<ContactInfoDrawerProps> = ({
  recipient,
  onClose,
  isBlocked = false,
  onBlock,
  onUnblock,
  isConnected = false,
  onRemoveConnection,
}) => {
  const [publicKeys, setPublicKeys] = useState<UserPublicKey[]>([]);
  const [loading, setLoading] = useState(false);
  const [showBlockConfirm, setShowBlockConfirm] = useState(false);
  const [blockActionBusy, setBlockActionBusy] = useState(false);
  const [showRemoveConfirm, setShowRemoveConfirm] = useState(false);
  const [removeActionBusy, setRemoveActionBusy] = useState(false);

  useEffect(() => {
    if (recipient) {
      setLoading(true);
      deviceApi
        .getUserPublicKeys(recipient.id)
        .then((data) => setPublicKeys(data))
        .catch(() => setPublicKeys([]))
        .finally(() => setLoading(false));
    }
  }, [recipient]);

  if (!recipient) return null;

  const handleConfirmBlock = async () => {
    if (!onBlock) return;
    setBlockActionBusy(true);
    try {
      await onBlock(recipient.id);
      setShowBlockConfirm(false);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to block user';
      alert(message);
    } finally {
      setBlockActionBusy(false);
    }
  };

  const handleUnblock = async () => {
    if (!onUnblock) return;
    setBlockActionBusy(true);
    try {
      await onUnblock(recipient.id);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to unblock user';
      alert(message);
    } finally {
      setBlockActionBusy(false);
    }
  };

  const handleConfirmRemoveConnection = async () => {
    if (!onRemoveConnection) return;
    setRemoveActionBusy(true);
    try {
      await onRemoveConnection(recipient.id);
      setShowRemoveConfirm(false);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to remove connection';
      alert(message);
    } finally {
      setRemoveActionBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-40 md:static md:inset-auto md:z-20 w-full md:w-80 h-full bg-white dark:bg-slate-900 border-l border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0 transition-colors duration-300 animate-slide-right overflow-y-auto text-slate-900 dark:text-white select-none">
      {/* Header */}
      <div className="h-16 px-4 border-b border-slate-200 dark:border-slate-800/80 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <button
            onClick={onClose}
            className="md:hidden p-1.5 -ml-1.5 text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            aria-label="Back"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <h3 className="font-bold text-base">Contact Info</h3>
        </div>
        <button
          onClick={onClose}
          className="hidden md:block p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          aria-label="Close"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Main Profile Info */}
      <div className="p-6 text-center border-b border-slate-200 dark:border-slate-800/80 space-y-3">
        <UserAvatar user={recipient} size="xl" className="mx-auto shadow-xl" />
        <div>
          <h2 className="text-lg font-bold">{recipient.displayName || recipient.username}</h2>
          <p className="text-xs text-slate-500 dark:text-slate-400 font-mono">@{recipient.username}</p>
        </div>
      </div>

      {/* E2EE Security Code Verification Box */}
      <div className="p-4 border-b border-slate-200 dark:border-slate-800/80 space-y-3">
        <div className="flex items-center gap-2 text-xs font-bold text-pink-600 dark:text-pink-400 uppercase tracking-wider">
          <ShieldCheck className="w-4 h-4" />
          <span>Encryption Verification</span>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
          Messages to this chat are secured with end-to-end encryption. You can verify the key fingerprint below.
        </p>

        {loading ? (
          <div className="text-xs text-slate-400 italic">Fetching security key fingerprint...</div>
        ) : publicKeys.length > 0 ? (
          <div className="bg-slate-50 dark:bg-slate-950 p-3 rounded-xl border border-slate-200 dark:border-slate-800 font-mono text-[11px] text-pink-600 dark:text-pink-300 space-y-1.5">
            <div className="text-slate-500 dark:text-slate-400 text-[10px] uppercase font-bold">Public Key Fingerprint</div>
            <div className="break-all bg-white dark:bg-black/40 p-2 rounded text-[10px] text-slate-800 dark:text-slate-300 border border-slate-200 dark:border-slate-800 select-all">
              {publicKeys[0].publicKey.substring(0, 80)}...
            </div>
            <div className="text-[10px] text-emerald-600 dark:text-emerald-400 font-semibold">
              Algorithm: {publicKeys[0].keyAlgorithm}
            </div>
          </div>
        ) : (
          <div className="text-xs text-slate-400">No active keys found.</div>
        )}
      </div>

      {/* Cryptographic Devices */}
      <div className="p-4 space-y-3">
        <div className="flex items-center gap-2 text-xs font-bold text-indigo-600 dark:text-indigo-400 uppercase tracking-wider">
          <Laptop className="w-4 h-4" />
          <span>Registered Endpoints ({publicKeys.length})</span>
        </div>

        {publicKeys.map((pk) => (
          <div key={pk.deviceId} className="p-3 rounded-xl bg-slate-50 dark:bg-slate-950/60 border border-slate-200 dark:border-slate-800 space-y-1">
            <div className="text-xs font-bold flex items-center justify-between">
              <span>{pk.deviceName}</span>
              <span className="text-[10px] text-indigo-600 dark:text-indigo-400 font-mono">Device #{pk.deviceId}</span>
            </div>
            <div className="text-[11px] text-slate-500 dark:text-slate-400 font-mono">Algorithm: {pk.keyAlgorithm}</div>
          </div>
        ))}
      </div>

      {/* Relationship: Remove Connection / Block / Unblock */}
      {(onBlock || onUnblock || (isConnected && onRemoveConnection)) && (
        <div className="p-4 border-t border-slate-200 dark:border-slate-800/80 space-y-3">
          {isConnected && onRemoveConnection && (
            <div className="space-y-2">
              <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                Relationship &middot; Connected
              </div>
              <button
                onClick={() => setShowRemoveConfirm(true)}
                disabled={removeActionBusy}
                className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
              >
                {removeActionBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <UserMinus className="w-4 h-4" />}
                Remove Connection
              </button>
            </div>
          )}

          {isBlocked ? (
            <button
              onClick={handleUnblock}
              disabled={blockActionBusy}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-slate-700 dark:text-slate-200 border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors disabled:opacity-50"
            >
              {blockActionBusy ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldOff className="w-4 h-4" />}
              Unblock {recipient.displayName || recipient.username}
            </button>
          ) : (
            <button
              onClick={() => setShowBlockConfirm(true)}
              disabled={blockActionBusy}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold text-red-600 dark:text-red-400 border border-red-200 dark:border-red-900/50 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors disabled:opacity-50"
            >
              <ShieldOff className="w-4 h-4" />
              Block {recipient.displayName || recipient.username}
            </button>
          )}
        </div>
      )}

      {showBlockConfirm && (
        <BlockUserConfirmDialog
          contactName={recipient.displayName || recipient.username}
          blocking={blockActionBusy}
          onCancel={() => setShowBlockConfirm(false)}
          onConfirm={handleConfirmBlock}
        />
      )}

      {showRemoveConfirm && (
        <RemoveConnectionConfirmDialog
          contactName={recipient.displayName || recipient.username}
          removing={removeActionBusy}
          onCancel={() => setShowRemoveConfirm(false)}
          onConfirm={handleConfirmRemoveConnection}
        />
      )}
    </div>
  );
};
