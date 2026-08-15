export class BrowserNotificationManager {
  private notificationsEnabled: boolean = true;

  constructor() {
    const saved = localStorage.getItem('connectx_browser_notifications');
    this.notificationsEnabled = saved === null ? true : saved === 'true';
  }

  public isSupported(): boolean {
    return 'Notification' in window || 'serviceWorker' in navigator;
  }

  public getPermissionStatus(): NotificationPermission {
    if (!('Notification' in window)) return 'denied';
    return Notification.permission;
  }

  public async requestPermission(): Promise<boolean> {
    if (!('Notification' in window)) return false;
    if (Notification.permission === 'granted') return true;
    try {
      const permission = await Notification.requestPermission();
      return permission === 'granted';
    } catch {
      return false;
    }
  }

  public isEnabled(): boolean {
    return this.notificationsEnabled && this.getPermissionStatus() === 'granted';
  }

  public setEnabled(enabled: boolean): void {
    this.notificationsEnabled = enabled;
    localStorage.setItem('connectx_browser_notifications', String(enabled));
    if (enabled && this.getPermissionStatus() === 'default') {
      this.requestPermission();
    }
  }

  public toggle(): boolean {
    const nextState = !this.notificationsEnabled;
    this.setEnabled(nextState);
    return nextState;
  }

  public async showNotification(
    title: string,
    options: {
      body: string;
      icon?: string;
      conversationId?: number;
      onClick?: () => void;
    }
  ): Promise<void> {
    if (!this.isEnabled()) return;

    const notificationOptions: NotificationOptions = {
      body: options.body,
      icon: options.icon || '/pwa-192x192.png',
      badge: '/pwa-192x192.png',
      tag: options.conversationId ? `conv-${options.conversationId}` : 'connectx-msg',
      vibrate: [200, 100, 200],
      data: {
        conversationId: options.conversationId,
      },
    } as NotificationOptions;

    // Primary path: Service Worker showNotification.
    // Chrome REQUIRES this when a SW is registered — new Notification() is blocked.
    if ('serviceWorker' in navigator) {
      try {
        const registration = await navigator.serviceWorker.ready;
        if (registration?.showNotification) {
          await registration.showNotification(title, notificationOptions);
          return; // Success — done
        }
      } catch (err) {
        // SW showNotification failed — log and try fallback
        console.warn('[ConnectX Notifications] SW showNotification failed, trying fallback:', err);
      }
    }

    // Fallback path: window Notification API.
    // Works on Firefox and browsers without a SW registered.
    // On Chrome with an active SW this will throw — catch and log.
    try {
      const notification = new Notification(title, notificationOptions);
      notification.onclick = () => {
        window.focus();
        if (options.onClick) {
          options.onClick();
        }
        notification.close();
      };
      setTimeout(() => notification.close(), 6000);
    } catch (e) {
      // Chrome blocks new Notification() when SW is registered — this is expected.
      // The SW push handler will still show notifications for background messages.
      console.info('[ConnectX Notifications] Foreground notification fallback skipped (expected in Chrome with SW):', (e as Error).message);
    }
  }
}

export const browserNotifications = new BrowserNotificationManager();

