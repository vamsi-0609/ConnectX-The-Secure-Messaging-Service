function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

// "Last seen 5 minutes ago" / "Last seen yesterday at 3:04 PM" / "Last seen Aug 12"
export function formatLastSeen(iso?: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;

  const now = Date.now();
  const diffMs = now - date.getTime();
  const diffMinutes = Math.floor(diffMs / (60 * 1000));

  if (diffMinutes < 1) return 'Last seen just now';
  if (diffMinutes < 60) return `Last seen ${diffMinutes} minute${diffMinutes === 1 ? '' : 's'} ago`;

  const diffHours = Math.floor(diffMinutes / 60);
  const today = startOfDay(new Date());
  const target = startOfDay(date);
  const diffDays = Math.round((today.getTime() - target.getTime()) / (1000 * 60 * 60 * 24));

  if (diffDays === 0) {
    return `Last seen ${diffHours} hour${diffHours === 1 ? '' : 's'} ago`;
  }
  const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (diffDays === 1) return `Last seen yesterday at ${time}`;
  if (diffDays < 7) return `Last seen ${date.toLocaleDateString(undefined, { weekday: 'long' })} at ${time}`;
  return `Last seen ${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}`;
}
