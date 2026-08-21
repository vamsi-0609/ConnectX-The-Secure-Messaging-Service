import { Message, UserPublicKey } from '../types';

export interface CachedConversationState {
  conversationId: number;
  messages: Message[];
  hasMore: boolean;
  oldestCursor: number | null;
  lastAccessedAt: number;
  lastFetchedAt: number;
}

const MAX_CACHED_CONVERSATIONS = 15;
const MAX_DECRYPTED_MESSAGES = 1000;
const MAX_PUBLIC_KEY_ENTRIES = 200;
const KEY_CACHE_TTL_MS = 10 * 60 * 1000; // 10 minutes

class ConversationMemoryCache {
  private conversationMap = new Map<number, CachedConversationState>();
  private publicKeyMap = new Map<number, { keys: UserPublicKey[]; fetchedAt: number }>();
  private decryptedTextMap = new Map<number, string>();

  // ── Conversation LRU Cache ────────────────────────────────────────────────

  public getConversation(conversationId: number): CachedConversationState | null {
    const entry = this.conversationMap.get(conversationId);
    if (!entry) return null;
    entry.lastAccessedAt = Date.now();
    return {
      ...entry,
      messages: [...entry.messages], // Return immutable copy
    };
  }

  public setConversation(
    conversationId: number,
    state: {
      messages: Message[];
      hasMore: boolean;
      oldestCursor?: number | null;
      lastFetchedAt?: number;
    }
  ): void {
    const now = Date.now();
    const oldestCursor =
      state.oldestCursor !== undefined
        ? state.oldestCursor
        : state.messages.length > 0
        ? state.messages[0].id
        : null;

    this.conversationMap.set(conversationId, {
      conversationId,
      messages: [...state.messages],
      hasMore: state.hasMore,
      oldestCursor,
      lastAccessedAt: now,
      lastFetchedAt: state.lastFetchedAt ?? now,
    });

    this.enforceConversationLimit(conversationId);
  }

  public updateConversationMessages(
    conversationId: number,
    updater: (prev: Message[]) => Message[]
  ): Message[] | null {
    const entry = this.conversationMap.get(conversationId);
    if (!entry) return null;
    entry.messages = updater([...entry.messages]);
    entry.lastAccessedAt = Date.now();
    return [...entry.messages];
  }

  public removeConversation(conversationId: number): void {
    this.conversationMap.delete(conversationId);
  }

  private enforceConversationLimit(activeConversationId?: number): void {
    if (this.conversationMap.size <= MAX_CACHED_CONVERSATIONS) return;

    let oldestId: number | null = null;
    let oldestTime = Infinity;

    for (const [id, entry] of this.conversationMap.entries()) {
      if (id === activeConversationId) continue;
      if (entry.lastAccessedAt < oldestTime) {
        oldestTime = entry.lastAccessedAt;
        oldestId = id;
      }
    }

    if (oldestId != null) {
      this.conversationMap.delete(oldestId);
    }
  }

  // ── Peer Public Key Cache ─────────────────────────────────────────────────

  public getPublicKeys(userId: number): UserPublicKey[] | null {
    const entry = this.publicKeyMap.get(userId);
    if (!entry) return null;
    if (Date.now() - entry.fetchedAt > KEY_CACHE_TTL_MS) {
      this.publicKeyMap.delete(userId);
      return null;
    }
    return entry.keys;
  }

  public setPublicKeys(userId: number, keys: UserPublicKey[]): void {
    this.publicKeyMap.set(userId, {
      keys,
      fetchedAt: Date.now(),
    });
    this.enforcePublicKeyLimit();
  }

  public invalidatePublicKeys(userId: number): void {
    this.publicKeyMap.delete(userId);
  }

  private enforcePublicKeyLimit(): void {
    if (this.publicKeyMap.size <= MAX_PUBLIC_KEY_ENTRIES) return;

    let oldestId: number | null = null;
    let oldestTime = Infinity;

    for (const [id, entry] of this.publicKeyMap.entries()) {
      if (entry.fetchedAt < oldestTime) {
        oldestTime = entry.fetchedAt;
        oldestId = id;
      }
    }

    if (oldestId != null) {
      this.publicKeyMap.delete(oldestId);
    }
  }

  // ── Session Decryption Cache ──────────────────────────────────────────────

  public getDecryptedText(messageId: number): string | null {
    return this.decryptedTextMap.get(messageId) ?? null;
  }

  public setDecryptedText(messageId: number, decryptedContent: string): void {
    if (this.decryptedTextMap.size >= MAX_DECRYPTED_MESSAGES) {
      // Remove oldest inserted entry
      const firstKey = this.decryptedTextMap.keys().next().value;
      if (firstKey !== undefined) {
        this.decryptedTextMap.delete(firstKey);
      }
    }
    this.decryptedTextMap.set(messageId, decryptedContent);
  }

  public deleteDecryptedText(messageId: number): void {
    this.decryptedTextMap.delete(messageId);
  }

  // ── Global Cache Reset (on signout) ───────────────────────────────────────

  public clearAll(): void {
    this.conversationMap.clear();
    this.publicKeyMap.clear();
    this.decryptedTextMap.clear();
  }
}

export const conversationCache = new ConversationMemoryCache();
