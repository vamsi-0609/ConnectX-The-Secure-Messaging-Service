let swRegistration: ServiceWorkerRegistration | null = null;

export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) {
    return null;
  }

  // In development mode, unregister any existing service worker to prevent proxy interference
  if (import.meta.env.DEV) {
    try {
      const registrations = await navigator.serviceWorker.getRegistrations();
      for (const registration of registrations) {
        await registration.unregister();
        console.log('[ConnectX SW] Unregistered Service Worker for development mode');
      }
    } catch {
      // Ignore in dev
    }
    return null;
  }

  try {
    const registration = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    swRegistration = registration;
    console.log('[ConnectX SW] Service Worker registered successfully with scope:', registration.scope);
    return registration;
  } catch (error) {
    console.warn('[ConnectX SW] Service Worker registration failed:', error);
    return null;
  }
}

export function getServiceWorkerRegistration(): ServiceWorkerRegistration | null {
  return swRegistration;
}
