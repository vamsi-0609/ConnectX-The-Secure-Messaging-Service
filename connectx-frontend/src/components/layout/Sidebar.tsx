import React from 'react';
import { Shield, Plus, Laptop, LogOut, MessageSquare, Radio, Wifi, WifiOff } from 'lucide-react';
import { User, Conversation, ConnectionStatus } from '../../types';

interface SidebarProps {
  currentUser: User;
  conversations: Conversation[];
  activeConversationId: number | null;
  connectionStatus: ConnectionStatus;
  onSelectConversation: (conv: Conversation) => void;
  onOpenSearch: () => void;
  onOpenDevices: () => void;
  onLogout: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({
  currentUser,
  conversations,
  activeConversationId,
  connectionStatus,
  onSelectConversation,
  onOpenSearch,
  onOpenDevices,
  onLogout,
}) => {
  const getRecipientUser = (conv: Conversation): User | null => {
    const otherMember = conv.members.find((m) => m.user.id !== currentUser.id);
    return otherMember ? otherMember.user : null;
  };

  return (
    <div className="w-80 h-full bg-gray-950 border-r border-gray-800 flex flex-col glass-panel select-none">
      {/* Header Branding */}
      <div className="p-4 border-b border-gray-800 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-xl bg-indigo-500/10 border border-indigo-500/20 text-indigo-400">
            <Shield className="w-5 h-5 text-indigo-400" />
          </div>
          <div>
            <h1 className="font-bold text-white tracking-tight text-base">CONNECTX</h1>
            <p className="text-[10px] text-pink-400 font-semibold tracking-wider">E2EE MESSAGING</p>
          </div>
        </div>

        {/* Real-Time WebSocket Connection Indicator */}
        <div
          className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold ${
            connectionStatus === 'CONNECTED'
              ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
              : 'bg-amber-500/10 text-amber-400 border border-amber-500/20'
          }`}
          title={`Backend WS Status: ${connectionStatus}`}
        >
          {connectionStatus === 'CONNECTED' ? (
            <>
              <Wifi className="w-3 h-3 text-emerald-400" />
              <span>Live</span>
            </>
          ) : (
            <>
              <WifiOff className="w-3 h-3 text-amber-400 animate-pulse" />
              <span>Connecting</span>
            </>
          )}
        </div>
      </div>

      {/* Action Buttons */}
      <div className="p-3 space-y-2 border-b border-gray-800">
        <button
          onClick={onOpenSearch}
          className="w-full btn btn-primary py-2.5 text-xs font-semibold"
        >
          <Plus className="w-4 h-4" />
          <span>New Encrypted Conversation</span>
        </button>

        <button
          onClick={onOpenDevices}
          className="w-full btn btn-secondary py-2 text-xs text-gray-300 hover:text-white"
        >
          <Laptop className="w-4 h-4 text-pink-400" />
          <span>Cryptographic Key Vault</span>
        </button>
      </div>

      {/* Conversations List */}
      <div className="flex-1 overflow-y-auto p-2 space-y-1">
        <div className="px-3 py-2 text-[11px] font-bold text-gray-500 uppercase tracking-wider">
          Direct Messages ({conversations.length})
        </div>

        {conversations.length === 0 ? (
          <div className="text-center py-8 text-xs text-gray-500 px-4">
            No active conversations. Click "New Encrypted Conversation" above to start!
          </div>
        ) : (
          conversations.map((conv) => {
            const recipient = getRecipientUser(conv);
            const isActive = conv.id === activeConversationId;
            if (!recipient) return null;

            return (
              <button
                key={conv.id}
                onClick={() => onSelectConversation(conv)}
                className={`w-full flex items-center gap-3 p-3 rounded-xl transition-all text-left ${
                  isActive
                    ? 'bg-indigo-600/20 border border-indigo-500/40 text-white'
                    : 'hover:bg-gray-900/60 border border-transparent text-gray-300'
                }`}
              >
                <div className="relative flex-shrink-0">
                  <div className="w-10 h-10 rounded-full bg-gradient-to-tr from-indigo-600 to-pink-500 flex items-center justify-center font-bold text-white text-sm">
                    {recipient.displayName?.charAt(0).toUpperCase() || recipient.username.charAt(0).toUpperCase()}
                  </div>
                  <span
                    className={`absolute bottom-0 right-0 w-2.5 h-2.5 rounded-full border-2 border-gray-950 ${
                      recipient.status === 'ONLINE' ? 'bg-emerald-500' : 'bg-gray-500'
                    }`}
                  />
                </div>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-sm truncate">{recipient.displayName || recipient.username}</span>
                  </div>
                  <span className="text-xs text-gray-500 font-mono truncate block">@{recipient.username}</span>
                </div>
              </button>
            );
          })
        )}
      </div>

      {/* Footer Current User Profile */}
      <div className="p-3 border-t border-gray-800 bg-gray-900/40 flex items-center justify-between">
        <div className="flex items-center gap-2.5 min-w-0">
          <div className="w-9 h-9 rounded-full bg-indigo-600 flex items-center justify-center font-bold text-white text-sm">
            {currentUser.displayName?.charAt(0).toUpperCase() || currentUser.username.charAt(0).toUpperCase()}
          </div>
          <div className="min-w-0">
            <div className="font-semibold text-xs text-white truncate">{currentUser.displayName}</div>
            <div className="text-[11px] text-gray-400 font-mono truncate">@{currentUser.username}</div>
          </div>
        </div>

        <button
          onClick={onLogout}
          className="p-2 text-gray-400 hover:text-rose-400 hover:bg-rose-500/10 rounded-lg transition-colors"
          title="Sign out"
        >
          <LogOut className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};
