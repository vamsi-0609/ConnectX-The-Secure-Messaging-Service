import React, { useEffect, useState } from 'react';
import { ShieldCheck, X, Laptop } from 'lucide-react';
import { User, UserPublicKey } from '../../types';
import { deviceApi } from '../../api/deviceApi';
import { UserAvatar } from '../common/UserAvatar';

interface ContactInfoDrawerProps {
  recipient: User | null;
  onClose: () => void;
}

export const ContactInfoDrawer: React.FC<ContactInfoDrawerProps> = ({ recipient, onClose }) => {
  const [publicKeys, setPublicKeys] = useState<UserPublicKey[]>([]);
  const [loading, setLoading] = useState(false);

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

  return (
    <div className="w-80 h-full bg-white dark:bg-slate-900 border-l border-slate-200 dark:border-slate-800/80 flex flex-col flex-shrink-0 z-20 transition-colors duration-300 animate-slide-right overflow-y-auto text-slate-900 dark:text-white">
      {/* Header */}
      <div className="h-16 px-4 border-b border-slate-200 dark:border-slate-800/80 flex items-center justify-between">
        <h3 className="font-bold text-base">Contact Info</h3>
        <button
          onClick={onClose}
          className="p-1.5 text-slate-400 hover:text-slate-700 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
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
            <div className="break-all bg-white dark:bg-black/40 p-2 rounded text-[10px] text-slate-800 dark:text-slate-300 border border-slate-200 dark:border-slate-800">
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
    </div>
  );
};
