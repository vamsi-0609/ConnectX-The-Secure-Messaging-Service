import React, { createContext, useContext, useEffect, useState } from 'react';
import { wsClient } from './WebSocketClient';
import { ConnectionStatus, WsEvent } from '../types';

interface WebSocketContextType {
  status: ConnectionStatus;
  isOffline: boolean;
  sendEvent: (event: WsEvent) => boolean;
  subscribe: (handler: (event: WsEvent) => void) => () => void;
  reconnect: () => void;
}

const WebSocketContext = createContext<WebSocketContextType | null>(null);

export const WebSocketProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [status, setStatus] = useState<ConnectionStatus>(wsClient.getStatus());
  const [isOffline, setIsOffline] = useState<boolean>(
    typeof navigator !== 'undefined' ? !navigator.onLine : false
  );

  useEffect(() => {
    const token = localStorage.getItem('connectx_token');
    if (token) {
      wsClient.setToken(token);
      wsClient.connect();
    }

    const unsubscribe = wsClient.onStatusChange((newStatus) => {
      setStatus(newStatus);
    });

    const handleOnline = () => setIsOffline(false);
    const handleOffline = () => setIsOffline(true);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    return () => {
      unsubscribe();
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  const sendEvent = (event: WsEvent) => {
    return wsClient.send(event);
  };

  const subscribe = (handler: (event: WsEvent) => void) => {
    return wsClient.onMessage(handler);
  };

  const reconnect = () => {
    const token = localStorage.getItem('connectx_token');
    if (token) {
      wsClient.setToken(token);
      wsClient.connect();
    }
  };

  return (
    <WebSocketContext.Provider value={{ status, isOffline, sendEvent, subscribe, reconnect }}>
      {children}
    </WebSocketContext.Provider>
  );
};

export const useWebSocket = () => {
  const context = useContext(WebSocketContext);
  if (!context) {
    throw new Error('useWebSocket must be used within a WebSocketProvider');
  }
  return context;
};
