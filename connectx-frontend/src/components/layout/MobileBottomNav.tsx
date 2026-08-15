import React from 'react';
import { MessageSquare, Search, Smartphone, Sun, Moon, LogOut } from 'lucide-react';
import { User } from '../../types';

interface MobileBottomNavProps {
  currentUser: User;
  activeTab: 'chats' | 'search' | 'devices';
  isDarkMode: boolean;
  onToggleTheme: () => void;
  onTabChange: (tab: 'chats' | 'search' | 'devices') => void;
  onLogout: () => void;
}

export const MobileBottomNav: React.FC<MobileBottomNavProps> = ({
  currentUser,
  activeTab,
  isDarkMode,
  onToggleTheme,
  onTabChange,
  onLogout,
}) => {
  return (
    <div className="md:hidden fixed bottom-0 left-0 right-0 h-16 bg-slate-950/95 border-t border-slate-800/80 backdrop-blur-xl flex items-center justify-around z-40 px-2 shadow-2xl select-none">
      <button
        onClick={() => onTabChange('chats')}
        className={`flex flex-col items-center gap-1 p-2 rounded-xl transition-all ${
          activeTab === 'chats' ? 'text-indigo-400 font-bold scale-105' : 'text-slate-400 hover:text-white'
        }`}
      >
        <MessageSquare className="w-5 h-5" />
        <span className="text-[10px]">Chats</span>
      </button>

      <button
        onClick={() => onTabChange('search')}
        className={`flex flex-col items-center gap-1 p-2 rounded-xl transition-all ${
          activeTab === 'search' ? 'text-indigo-400 font-bold scale-105' : 'text-slate-400 hover:text-white'
        }`}
      >
        <Search className="w-5 h-5" />
        <span className="text-[10px]">Discover</span>
      </button>

      <button
        onClick={() => onTabChange('devices')}
        className={`flex flex-col items-center gap-1 p-2 rounded-xl transition-all ${
          activeTab === 'devices' ? 'text-indigo-400 font-bold scale-105' : 'text-slate-400 hover:text-white'
        }`}
      >
        <Smartphone className="w-5 h-5" />
        <span className="text-[10px]">Devices</span>
      </button>

      <button
        onClick={onToggleTheme}
        className="flex flex-col items-center gap-1 p-2 rounded-xl text-slate-400 hover:text-white transition-all"
        title="Toggle Theme"
      >
        {isDarkMode ? <Sun className="w-5 h-5 text-amber-400" /> : <Moon className="w-5 h-5" />}
        <span className="text-[10px]">Theme</span>
      </button>

      <button
        onClick={onLogout}
        className="flex flex-col items-center gap-1 p-2 rounded-xl text-rose-400 hover:text-rose-300 transition-all"
        title="Log Out"
      >
        <LogOut className="w-5 h-5" />
        <span className="text-[10px]">Logout</span>
      </button>
    </div>
  );
};
