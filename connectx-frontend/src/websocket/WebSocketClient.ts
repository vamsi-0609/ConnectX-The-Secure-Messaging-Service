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

  // Track active conversation IDs and active subscriptions
  private activeConversationIds: Set<number> = new Set();
  private conversationSubs: Map<number, StompSubscription> = new Map();

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
    }
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

    if (this.stompClient && this.stompClient.active) {
      return;
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

      // Clear stale subscription references on new connection
      this.conversationSubs.clear();

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

      // 3. Resubscribe to all active conversation topics automatically
      this.activeConversationIds.forEach((id) => {
        this.performConversationSub(id);
      });
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

  public subscribeToConversation(conversationId: number) {
    this.activeConversationIds.add(conversationId);
    if (this.stompClient && this.stompClient.connected) {
      this.performConversationSub(conversationId);
    }
  }

  private performConversationSub(conversationId: number) {
    if (!this.stompClient || !this.stompClient.connected) return;
    if (this.conversationSubs.has(conversationId)) return;

    const sub = this.stompClient.subscribe(`/topic/conversation/${conversationId}`, (message: IMessage) => {
      try {
        const wsEvent: WsEvent = JSON.parse(message.body);
        this.notifyMessageListeners(wsEvent);
      } catch (err) {
        console.error('[ConnectX STOMP] Failed to parse topic message:', err);
      }
    });

    this.conversationSubs.set(conversationId, sub);
  }

  public unsubscribeFromConversation(conversationId: number) {
    this.activeConversationIds.delete(conversationId);
    const sub = this.conversationSubs.get(conversationId);
    if (sub) {
      sub.unsubscribe();
      this.conversationSubs.delete(conversationId);
    }
  }

  public disconnect() {
    this.isIntentionalDisconnect = true;
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.activeConversationIds.clear();
    this.conversationSubs.forEach((sub) => sub.unsubscribe());
    this.conversationSubs.clear();
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
   * Must NOT go through /app/message.send (which requires ciphertext).
   */
  public sendRead(conversationId: number): boolean {
    if (this.stompClient && this.stompClient.connected) {
      const event: WsEvent = {
        type: 'MESSAGE_READ',
        payload: { conversationId },
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
