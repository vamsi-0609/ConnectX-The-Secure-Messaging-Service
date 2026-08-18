import React from 'react';
import { ShieldCheck, Key, Lock, Loader2 } from 'lucide-react';

interface KeyOnboardingModalProps {
  statusText: string;
}

export const KeyOnboardingModal: React.FC<KeyOnboardingModalProps> = ({ statusText }) => {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 backdrop-blur-md p-4 animate-fadeIn select-none">
      <div className="w-full max-w-md p-6 sm:p-8 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl text-center space-y-6 shadow-2xl text-slate-900 dark:text-white animate-pop-in">
        <div className="relative inline-flex items-center justify-center w-20 h-20 rounded-3xl bg-violet-500/10 border border-violet-500/20 text-violet-600 dark:text-violet-400">
          <ShieldCheck className="w-10 h-10 text-violet-500" />
        </div>

        <div className="space-y-1.5">
          <h2 className="text-xl font-bold tracking-tight">Setting Up Your Secure Session</h2>
          <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
            Preparing your private device security keys for end-to-end encryption.
          </p>
        </div>

        <div className="bg-slate-50 dark:bg-slate-800/40 p-4 rounded-2xl border border-slate-100 dark:border-slate-800/60 space-y-3 text-left">
          <div className="flex items-center gap-3 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
            <Key className="w-4 h-4 text-emerald-500 flex-shrink-0" />
            <span>Device security keys generated</span>
          </div>
          <div className="flex items-center gap-3 text-xs font-semibold text-violet-600 dark:text-violet-400">
            <Lock className="w-4 h-4 text-violet-500 flex-shrink-0" />
            <span>Private keys secured on this device</span>
          </div>
          <div className="flex items-center gap-3 text-xs font-semibold text-slate-600 dark:text-slate-300">
            <Loader2 className="w-4 h-4 animate-spin text-violet-500 flex-shrink-0" />
            <span>{statusText || 'Finalizing secure connection...'}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

