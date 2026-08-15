import { config } from '../config/environment';

function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');

  const rawData = window.atob(base64);
  const outputArray = new Uint8Array(rawData.length);

  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

/**
 * Register or re-register the Web Push subscription with the backend.
 * Safe to call multiple times — idempotent when subscription endpoint is unchanged.
 * On VAPID key mismatch (server restarted with new keys), unsubscribes and re-subscribes.
 */
export async function registerWebPushSubscription(): Promise<boolean> {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
    console.log('[ConnectX Push] Web Push is not supported in this browser.');
    return false;
  }

  const token = localStorage.getItem('connectx_token');
  if (!token) return false;

  try {
    const registration = await navigator.serviceWorker.ready;
    if (!registration) return false;

    // Fetch VAPID public key from backend API
    const response = await fetch(`${config.apiBaseUrl}/push/vapid-public-key`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    if (!response.ok) {
      console.warn('[ConnectX Push] Failed to fetch VAPID public key — status:', response.status);
      return false;
    }

    const data = await response.json();
    const vapidPublicKey = data.data ? data.data.vapidPublicKey : null;

    if (!vapidPublicKey) {
      console.warn('[ConnectX Push] VAPID public key was empty in response');
      return false;
    }

    const convertedVapidKey = urlBase64ToUint8Array(vapidPublicKey);

    let subscription = await registration.pushManager.getSubscription();
    if (!subscription) {
      console.log('[ConnectX Push] No existing subscription found — creating new one.');
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: convertedVapidKey.buffer as ArrayBuffer,
      });
    }

    // Register subscription with backend
    const subResponse = await fetch(`${config.apiBaseUrl}/push/subscribe`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(subscription),
    });

    if (subResponse.ok) {
      console.log('[ConnectX Push] Push subscription registered/confirmed with backend.');
      return true;
    } else if (subResponse.status === 401 || subResponse.status === 403) {
      console.warn('[ConnectX Push] Backend rejected subscription (auth error) — status:', subResponse.status);
    } else if (subResponse.status === 404 || subResponse.status === 410) {
      // Server doesn't recognize this subscription — VAPID key mismatch.
      // Unsubscribe and retry once.
      console.warn('[ConnectX Push] Subscription rejected by backend (possible VAPID key rotation) — resubscribing.');
      await subscription.unsubscribe();
      const newSub = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: convertedVapidKey.buffer as ArrayBuffer,
      });
      const retryResponse = await fetch(`${config.apiBaseUrl}/push/subscribe`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(newSub),
      });
      if (retryResponse.ok) {
        console.log('[ConnectX Push] Push subscription re-registered successfully after key rotation.');
        return true;
      }
    } else {
      console.warn('[ConnectX Push] Unexpected backend response:', subResponse.status);
    }
  } catch (err) {
    console.warn('[ConnectX Push] Failed to register Web Push subscription:', err);
  }
  return false;
}

