import { Message, User } from '../types';

// Keeps filenames portable across OSes (no / \ : * ? " < > |) and collapses whatever's left into
// something readable, e.g. "Madhu Jana" -> "Madhu-Jana".
function sanitizeFilenamePart(name: string): string {
  const cleaned = name
    .trim()
    .replace(/[^a-zA-Z0-9._ -]+/g, '')
    .trim()
    .replace(/\s+/g, '-');
  return cleaned || 'Chat';
}

export function buildChatExportFilename(otherUser: Pick<User, 'displayName' | 'username'>, exportedAt: Date = new Date()): string {
  const namePart = sanitizeFilenamePart(otherUser.displayName || otherUser.username);
  const datePart = exportedAt.toISOString().slice(0, 10);
  return `ConnectX-${namePart}-${datePart}.txt`;
}

function formatExportDate(date: Date): string {
  return date.toLocaleDateString('en-US', { day: '2-digit', month: 'short', year: 'numeric' });
}

function formatMessageTimestamp(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: true,
  });
}

// Only the message types that actually exist in this app (TEXT/IMAGE/LOCATION/DOCUMENT) --
// never invent placeholders for types the backend doesn't support.
function formatMessageBody(msg: Message): string {
  if (msg.deletedForEveryone) {
    return '[This message was deleted]';
  }
  switch (msg.messageType) {
    case 'IMAGE':
      return '[Photo]';
    case 'DOCUMENT':
      return '[Document]';
    case 'LOCATION':
      return '[Location]';
    case 'TEXT':
    default:
      // Never bypass the E2EE pipeline: if it wasn't decrypted (missing key material, decryption
      // failure, or a page that was never fetched/decrypted), say so instead of guessing.
      return msg.decryptedContent ? msg.decryptedContent : '[Encrypted message]';
  }
}

export function buildChatExportText(params: {
  currentUser: Pick<User, 'id' | 'displayName' | 'username'>;
  otherUser: Pick<User, 'displayName' | 'username'>;
  messages: Message[]; // chronological, oldest first
  exportedAt?: Date;
}): string {
  const { currentUser, otherUser, messages, exportedAt = new Date() } = params;
  const otherName = otherUser.displayName || otherUser.username;
  const lines: string[] = [
    'ConnectX Chat Export',
    '====================',
    '',
    `Conversation: ${otherName}`,
    `Exported: ${formatExportDate(exportedAt)}`,
    '',
    '----------------------------------------',
    '',
  ];

  for (const msg of messages) {
    const senderName = msg.senderUserId === currentUser.id ? (currentUser.displayName || currentUser.username) : (msg.senderUsername || otherName);
    lines.push(senderName);
    lines.push(formatMessageTimestamp(msg.sentAt));
    lines.push(formatMessageBody(msg));
    lines.push('');
  }

  lines.push('----------------------------------------');
  return lines.join('\n');
}

// Client-side-only file save -- no upload, no network call, matches restrictSaving conventions
// used elsewhere for locally-generated content (see ImageViewerModal's save path).
export function downloadTextFile(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}
