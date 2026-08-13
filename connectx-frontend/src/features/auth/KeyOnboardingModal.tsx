import React from 'react';
import { ShieldCheck, Key, Lock, Loader2 } from 'lucide-react';

interface KeyOnboardingModalProps {
  statusText: string;
}

export const KeyOnboardingModal: React.FC<KeyOnboardingModalProps> = ({ statusText }) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-md p-4 animate-fade-in">
      <div className="glass-panel-glow w-full max-w-md p-6 rounded-2xl text-center space-y-6">
        <div className="relative inline-flex items-center justify-center w-20 h-20 rounded-full bg-indigo-500/10 border border-indigo-500/30 text-indigo-400">
          <ShieldCheck className="w-10 h-10 animate-crypto-pulse text-pink-400" />
        </div>

        <div className="space-y-2">
          <h2 className="text-xl font-bold text-white tracking-wide">Establishing Cryptographic Context</h2>
          <p className="text-sm text-gray-400">
            Generating non-extractable E2EE Web Crypto key pair in browser IndexedDB.
          </p>
        </div>

        <div className="bg-gray-900/60 p-4 rounded-xl border border-gray-800 space-y-3 text-left">
          <div className="flex items-center gap-3 text-xs text-emerald-400">
            <Key className="w-4 h-4 text-emerald-400 flex-shrink-0" />
            <span>ECDH P-256 Keypair Generated (Client Browser)</span>
          </div>
          <div className="flex items-center gap-3 text-xs text-indigo-400">
            <Lock className="w-4 h-4 text-indigo-400 flex-shrink-0" />
            <span>Private Key Secured in IndexedDB Vault</span>
          </div>
          <div className="flex items-center gap-3 text-xs text-pink-400">
            <Loader2 className="w-4 h-4 animate-spin text-pink-400 flex-shrink-0" />
            <span>{statusText || 'Syncing Public Key with Spring Boot backend...'}</span>
          </div>
        </div>
      </div>
    </div>
  );
};
