import { config } from '../config/environment';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { WsEvent, ConnectionStatus } from '../types';

type MessageHandler = (event: WsEvent) => void;
type StatusHandler = (status: ConnectionStatus) => void;

const RECONNECT_DELAYS_MS = [1000, 2000, 4000, 8000, 15000, 30000];

export class WebSocketClient {
  private stompClient: Client | null = null;
  private status: ConnectionStatus = 'DISCONNECTED';
  private token: string | null = null;

  private messageListeners: Set<MessageHandler> = new Set();
  private statusListeners: Set<StatusHandler> = new Set();

  // Track only the currently active conversation ID and its subscription
  private currentActiveConversationId: number | null = null;
  private activeConversationSub: StompSubscription | null = null;

  // Reconnect backoff state
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private isIntentionalDisconnect = false;

  constructor() {
    this.token = localStorage.getItem('connectx_token');
    if (typeof window !== 'undefined') {
      window.addEventListener('connectx_token_refreshed', (e: Event) => {
        const customEvent = e as CustomEvent<{ token: string }>;
        if (customEvent.detail?.token) {
          this.token = customEvent.detail.token;
          if (this.status === 'ERROR' || this.status === 'DISCONNECTED') {
            this.connect();
          }
        }
      });

      // PWA/tab resume recovery: when the app comes back to the foreground or
      // network comes back, check the *actual* connection state and, only if it
      // isn't CONNECTED, kick a reconnect. connect() itself is the de-dup guard
      // (it no-ops while already CONNECTING/CONNECTED), so it's safe for all of
      // these to fire together without creating multiple sockets.
      const handleResume = () => this.checkConnectionOnResume();
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
          handleResume();
        }
      });
      window.addEventListener('focus', handleResume);
      window.addEventListener('online', handleResume);
    }
  }

  /**
   * Called on visibilitychange/focus/online. Not a blind reconnect: if we're
   * already connected, this is a no-op. Otherwise it cancels any pending
   * backoff timer and reconnects immediately instead of waiting it out.
   */
  public checkConnectionOnResume() {
    if (!this.token || this.isIntentionalDisconnect) {
      return;
    }
    if (this.status === 'CONNECTED') {
      return;
    }
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.connect();
  }

  public setToken(token: string | null) {
    this.token = token;
    if (!token && this.stompClient) {
      this.disconnect();
    }
  }

  public connect() {
    if (!this.token) {
      this.updateStatus('DISCONNECTED');
      return;
    }

    // Guard on our own status, not stompClient.active: stompjs's `active` flag only
    // flips to false via an explicit deactivate() call, so after any *unexpected*
    // close (network drop, backgrounded tab, server restart) it stays permanently
    // true even though the socket is dead — which previously made this guard block
    // every future reconnect attempt forever once a single disconnect occurred.
    if (this.status === 'CONNECTING' || this.status === 'CONNECTED') {
      return;
    }

    if (this.stompClient) {
      this.stompClient.deactivate();
      this.stompClient = null;
    }

    this.isIntentionalDisconnect = false;
    this.updateStatus('CONNECTING');

    const sockJsUrl = this.resolveSockJsUrl();

    // Disable STOMP's built-in reconnect — we manage it ourselves with bounded backoff
    this.stompClient = new Client({
      webSocketFactory: () => new SockJS(sockJsUrl),
      connectHeaders: {
        Authorization: `Bearer ${this.token}`,
        token: this.token,
      },
      reconnectDelay: 0, // We manage reconnect manually with bounded backoff
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
    });

    this.stompClient.onConnect = () => {
      console.log('[ConnectX STOMP] Connected to backend STOMP broker');
      this.reconnectAttempt = 0; // Reset backoff on successful connection
      this.updateStatus('CONNECTED');

      // Clear stale subscription reference on new connection
      this.activeConversationSub = null;

      // 1. Subscribe to User Incoming Messages Queue (/user/queue/messages)
      this.stompClient?.subscribe('/user/queue/messages', (message: IMessage) => {
        try {
          const wsEvent: WsEvent = JSON.parse(message.body);
          this.notifyMessageListeners(wsEvent);
        } catch (err) {
          console.error('[ConnectX STOMP] Failed to parse message body:', err);
        }
      });

      // 2. Subscribe to User Acknowledgments Queue (/user/queue/acks)
      this.stompClient?.subscribe('/user/queue/acks', (message: IMessage) => {
        try {
          const wsEvent: WsEvent = JSON.parse(message.body);
          this.notifyMessageListeners(wsEvent);
        } catch (err) {
          console.error('[ConnectX STOMP] Failed to parse ACK body:', err);
        }
      });

      // 3. Resubscribe to the currently active conversation topic if any
      if (this.currentActiveConversationId != null) {
        this.performConversationSub(this.currentActiveConversationId);
      }
    };

    this.stompClient.onStompError = (frame) => {
      console.error('[ConnectX STOMP] Broker error:', frame.headers['message'], frame.body);
      this.updateStatus('ERROR');
    };

    this.stompClient.onWebSocketClose = () => {
      this.updateStatus('DISCONNECTED');
      if (!this.isIntentionalDisconnect && this.token) {
        this.scheduleReconnect();
      }
    };

    this.stompClient.activate();
  }

  private scheduleReconnect() {
    // Clear any existing pending reconnect timer
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    const delayMs = RECONNECT_DELAYS_MS[Math.min(this.reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)];
    this.reconnectAttempt++;
    console.log(`[ConnectX STOMP] Scheduling reconnect in ${delayMs}ms (attempt ${this.reconnectAttempt})`);

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.isIntentionalDisconnect && this.token) {
        this.connect();
      }
    }, delayMs);
  }

  /**
   * Set the currently active conversation topic subscription.
   * Automatically unsubscribes from the previous conversation to prevent subscription leaks.
   */
  public setActiveConversation(conversationId: number | null) {
    if (this.currentActiveConversationId === conversationId) {
      return;
    }

    // Unsubscribe from previous conversation topic
    if (this.activeConversationSub) {
      try {
        this.activeConversationSub.unsubscribe();
      } catch (err) {
        console.warn('[ConnectX STOMP] Error unsubscribing from conversation topic:', err);
      }
      this.activeConversationSub = null;
    }

    this.currentActiveConversationId = conversationId;

    if (conversationId != null && this.stompClient && this.stompClient.connected) {
      this.performConversationSub(conversationId);
    }
  }

  public subscribeToConversation(conversationId: number) {
    this.setActiveConversation(conversationId);
  }

  private performConversationSub(conversationId: number) {
    if (!this.stompClient || !this.stompClient.connected) return;

    if (this.activeConversationSub) {
      this.activeConversationSub.unsubscribe();
      this.activeConversationSub = null;
    }

    this.activeConversationSub = this.stompClient.subscribe(`/topic/conversation/${conversationId}`, (message: IMessage) => {
      try {
        const wsEvent: WsEvent = JSON.parse(message.body);
        this.notifyMessageListeners(wsEvent);
      } catch (err) {
        console.error('[ConnectX STOMP] Failed to parse topic message:', err);
      }
    });
  }

  public unsubscribeFromConversation(conversationId: number) {
    if (this.currentActiveConversationId === conversationId) {
      this.setActiveConversation(null);
    }
  }

  public disconnect() {
    this.isIntentionalDisconnect = true;
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.activeConversationSub) {
      this.activeConversationSub.unsubscribe();
      this.activeConversationSub = null;
    }
    this.currentActiveConversationId = null;
    if (this.stompClient) {
      this.stompClient.deactivate();
      this.stompClient = null;
    }
    this.updateStatus('DISCONNECTED');
  }

  /**
   * Send an encrypted TEXT message to /app/message.send
   */
  public send(event: WsEvent): boolean {
    if (this.stompClient && this.stompClient.connected) {
      this.stompClient.publish({
        destination: '/app/message.send',
        body: JSON.stringify(event),
      });
      return true;
    }
    console.warn('[ConnectX STOMP] Cannot send message - STOMP client is not connected');
    return false;
  }

  /**
   * Send a MESSAGE_READ event to the correct /app/message.read destination.
   * Can include maxMessageId / upToMessageId for bounded bulk read.
   */
  public sendRead(conversationId: number, maxMessageId?: number): boolean {
    if (this.stompClient && this.stompClient.connected) {
      const event: WsEvent = {
        type: 'MESSAGE_READ',
        payload: {
          conversationId,
          ...(maxMessageId ? { maxMessageId, upToMessageId: maxMessageId } : {}),
        },
      };
      this.stompClient.publish({
        destination: '/app/message.read',
        body: JSON.stringify(event),
      });
      return true;
    }
    return false;
  }

  /**
   * Send a MESSAGE_DELIVERED event to the correct /app/message.delivered destination.
   * Must NOT go through /app/message.send (which requires ciphertext).
   */
  public sendDelivered(messageId: number): boolean {
    if (this.stompClient && this.stompClient.connected) {
      const event: WsEvent = {
        type: 'MESSAGE_DELIVERED',
        payload: { messageId },
      };
      this.stompClient.publish({
        destination: '/app/message.delivered',
        body: JSON.stringify(event),
      });
      return true;
    }
    return false;
  }

  /**
   * Send a TYPING_INDICATOR event to the correct /app/typing destination.
   * Ephemeral, no ack expected — silently no-ops if the socket isn't connected.
   */
  public sendTyping(conversationId: number, isTyping: boolean): boolean {
    if (this.stompClient && this.stompClient.connected) {
      const event: WsEvent = {
        type: 'TYPING_INDICATOR',
        payload: { conversationId, isTyping },
      };
      this.stompClient.publish({
        destination: '/app/typing',
        body: JSON.stringify(event),
      });
      return true;
    }
    return false;
  }

  public onMessage(handler: MessageHandler): () => void {
    this.messageListeners.add(handler);
    return () => this.messageListeners.delete(handler);
  }

  public onStatusChange(handler: StatusHandler): () => void {
    this.statusListeners.add(handler);
    handler(this.status);
    return () => this.statusListeners.delete(handler);
  }

  public getStatus(): ConnectionStatus {
    return this.status;
  }

  private updateStatus(newStatus: ConnectionStatus) {
    this.status = newStatus;
    this.statusListeners.forEach((handler) => handler(newStatus));
  }

  private notifyMessageListeners(event: WsEvent) {
    this.messageListeners.forEach((handler) => handler(event));
  }

  private resolveSockJsUrl(): string {
    const configured = config.wsBaseUrl;
    if (configured && !configured.includes('localhost:8080')) {
      return configured.replace(/^ws:/i, 'http:').replace(/^wss:/i, 'https:').replace(/\/$/, '');
    }

    const isHttps = typeof window !== 'undefined' && window.location.protocol === 'https:';
    const httpProtocol = isHttps ? 'https:' : 'http:';
    const host = typeof window !== 'undefined' ? window.location.host : 'localhost:5173';
    return `${httpProtocol}//${host}/ws`;
  }
}

export const wsClient = new WebSocketClient();
