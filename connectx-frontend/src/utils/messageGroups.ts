import { Message } from '../types';

export type MessageGroupItem =
  | { type: 'date'; label: string; key: string }
  | { type: 'message'; message: Message; isSelf: boolean; isGroupedWithPrev: boolean; isGroupedWithNext: boolean; key: string };

function startOfDay(date: Date): Date {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d;
}

export function formatDateSeparator(date: Date): string {
  const today = startOfDay(new Date());
  const target = startOfDay(date);
  const diffDays = Math.round((today.getTime() - target.getTime()) / (1000 * 60 * 60 * 24));

  if (diffDays === 0) return 'Today';
  if (diffDays === 1) return 'Yesterday';

  return date.toLocaleDateString(undefined, {
    month: 'long',
    day: 'numeric',
    year: date.getFullYear() !== today.getFullYear() ? 'numeric' : undefined,
  });
}

export function formatConversationTime(iso?: string): string {
  if (!iso) return '';
  const date = new Date(iso);
  const today = startOfDay(new Date());
  const target = startOfDay(date);
  const diffDays = Math.round((today.getTime() - target.getTime()) / (1000 * 60 * 60 * 24));

  if (diffDays === 0) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  if (diffDays === 1) return 'Yesterday';
  if (diffDays < 7) {
    return date.toLocaleDateString(undefined, { weekday: 'short' });
  }
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function buildMessageGroups(messages: Message[], currentUserId: number): MessageGroupItem[] {
  const sorted = [...messages].sort(
    (a, b) => new Date(a.sentAt).getTime() - new Date(b.sentAt).getTime()
  );

  const groups: MessageGroupItem[] = [];
  let lastDateLabel: string | null = null;

  sorted.forEach((message, index) => {
    const sentDate = new Date(message.sentAt);
    const dateLabel = formatDateSeparator(sentDate);

    if (dateLabel !== lastDateLabel) {
      groups.push({ type: 'date', label: dateLabel, key: `date-${dateLabel}-${message.id}` });
      lastDateLabel = dateLabel;
    }

    const isSelf = message.senderUserId === currentUserId;
    const prev = sorted[index - 1];
    const next = sorted[index + 1];

    const isGroupedWithPrev =
      !!prev &&
      prev.senderUserId === message.senderUserId &&
      formatDateSeparator(new Date(prev.sentAt)) === dateLabel &&
      new Date(message.sentAt).getTime() - new Date(prev.sentAt).getTime() < 5 * 60 * 1000;

    const isGroupedWithNext =
      !!next &&
      next.senderUserId === message.senderUserId &&
      formatDateSeparator(new Date(next.sentAt)) === dateLabel &&
      new Date(next.sentAt).getTime() - new Date(message.sentAt).getTime() < 5 * 60 * 1000;

    groups.push({
      type: 'message',
      message,
      isSelf,
      isGroupedWithPrev,
      isGroupedWithNext,
      key: `msg-${message.id}`,
    });
  });

  return groups;
}
