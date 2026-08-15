import React from 'react';
import { MessageSquare, Users, Laptop, LogOut, Sun, Moon, Bell, BellOff } from 'lucide-react';
import { User, ConnectionStatus } from '../../types';
import { ConnectXLogo } from '../common/ConnectXLogo';

interface NavigationRailProps {
  currentUser: User;
  activeTab: 'chats' | 'search' | 'devices';
  connectionStatus: ConnectionStatus;
  isDarkMode: boolean;
  notificationsEnabled?: boolean;
  onToggleTheme: () => void;
  onToggleNotifications?: () => void;
  onTabChange: (tab: 'chats' | 'search' | 'devices') => void;
  onLogout: () => void;
}

export const NavigationRail: React.FC<NavigationRailProps> = ({
  currentUser,
  activeTab,
  connectionStatus,
  isDarkMode,
  notificationsEnabled = true,
  onToggleTheme,
  onToggleNotifications,
  onTabChange,
  onLogout,
}) => {
  return (
    <div className="w-16 h-full bg-white dark:bg-slate-950 border-r border-slate-200 dark:border-slate-800/80 flex flex-col items-center justify-between py-4 z-20 flex-shrink-0 transition-colors duration-300">
      {/* Top Section: Logo & Navigation Options */}
      <div className="flex flex-col items-center gap-6">
        {/* ConnectX canonical brand icon */}
        <div className="flex items-center justify-center">
          <ConnectXLogo size="md" variant="gradient" />
        </div>

        {/* Navigation Action Buttons */}
        <div className="flex flex-col items-center gap-3">
          <button
            onClick={() => onTabChange('chats')}
            className={`p-3 rounded-2xl transition-all relative group flex items-center justify-center ${
              activeTab === 'chats'
                ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/30'
                : 'text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/60'
            }`}
            title="Chats"
          >
            <MessageSquare className="w-5 h-5" />
            <span className="absolute left-16 bg-slate-900 text-white text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
              Chats
            </span>
          </button>

          <button
            onClick={() => onTabChange('search')}
            className={`p-3 rounded-2xl transition-all relative group flex items-center justify-center ${
              activeTab === 'search'
                ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/30'
                : 'text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/60'
            }`}
            title="Discover Users"
          >
            <Users className="w-5 h-5" />
            <span className="absolute left-16 bg-slate-900 text-white text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
              Discover Users
            </span>
          </button>

          <button
            onClick={() => onTabChange('devices')}
            className={`p-3 rounded-2xl transition-all relative group flex items-center justify-center ${
              activeTab === 'devices'
                ? 'bg-pink-600 text-white shadow-lg shadow-pink-600/30'
                : 'text-slate-500 dark:text-slate-400 hover:text-pink-600 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800/60'
            }`}
            title="Cryptographic Key Vault"
          >
            <Laptop className="w-5 h-5" />
            <span className="absolute left-16 bg-slate-900 text-white text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
              Device Vault
            </span>
          </button>
        </div>
      </div>

      {/* Bottom Section: Notifications, Theme Toggle, Connection Indicator, User Profile & Logout */}
      <div className="flex flex-col items-center gap-4">
        {/* Notifications Switcher */}
        {onToggleNotifications && (
          <button
            onClick={onToggleNotifications}
            className="p-2.5 rounded-2xl bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:scale-105 transition-all relative group"
            title={notificationsEnabled ? 'Mute Notifications & Sound' : 'Enable Notifications & Sound'}
          >
            {notificationsEnabled ? (
              <Bell className="w-5 h-5 text-indigo-500" />
            ) : (
              <BellOff className="w-5 h-5 text-slate-400" />
            )}
            <span className="absolute left-16 bg-slate-900 text-white text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
              {notificationsEnabled ? 'Notifications On' : 'Notifications Muted'}
            </span>
          </button>
        )}

        {/* Light / Dark Mode Switcher */}
        <button
          onClick={onToggleTheme}
          className="p-2.5 rounded-2xl bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:scale-105 transition-all relative group"
          title={isDarkMode ? 'Switch to Light Mode' : 'Switch to Dark Mode'}
        >
          {isDarkMode ? <Sun className="w-5 h-5 text-amber-400" /> : <Moon className="w-5 h-5 text-indigo-600" />}
          <span className="absolute left-16 bg-slate-900 text-white text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
            {isDarkMode ? 'Light Mode' : 'Dark Mode'}
          </span>
        </button>

        {/* WebSocket Status Indicator Dot */}
        <div
          className={`w-3 h-3 rounded-full border-2 border-white dark:border-slate-950 ${
            connectionStatus === 'CONNECTED'
              ? 'bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.8)]'
              : 'bg-amber-500 animate-ping'
          }`}
          title={`WebSocket Status: ${connectionStatus}`}
        />

        {/* User Profile Avatar */}
        <div className="relative group cursor-pointer" title={currentUser.displayName || currentUser.username}>
          <div className="w-10 h-10 rounded-full bg-gradient-to-tr from-indigo-600 to-pink-500 flex items-center justify-center font-bold text-white text-sm shadow-md">
            {currentUser.displayName?.charAt(0).toUpperCase() || currentUser.username.charAt(0).toUpperCase()}
          </div>
          <span className="absolute left-16 bg-slate-900 text-white text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
            @{currentUser.username}
          </span>
        </div>

        {/* Logout Button */}
        <button
          onClick={onLogout}
          className="p-3 text-slate-400 hover:text-rose-500 hover:bg-rose-500/10 rounded-2xl transition-all relative group"
          title="Sign Out"
        >
          <LogOut className="w-5 h-5" />
          <span className="absolute left-16 bg-slate-900 text-rose-400 text-xs font-semibold px-2.5 py-1 rounded-md opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none whitespace-nowrap shadow-md z-30">
            Sign Out
          </span>
        </button>
      </div>
    </div>
  );
};
