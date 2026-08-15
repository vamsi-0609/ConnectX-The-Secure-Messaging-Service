/* ConnectX Service Worker v2 — Smart Cache + Background Push Notifications */

const CACHE_NAME = 'connectx-v2';

// Static assets that rarely change — safe for cache-first
const IMMUTABLE_ASSETS = [
  '/pwa-192x192.png',
  '/pwa-512x512.png',
  '/favicon.svg',
  '/manifest.json',
];

// App shell files — network-first so new deployments are always picked up
const APP_SHELL = ['/', '/index.html'];

// ── Install: pre-cache known immutable assets ─────────────────────────────
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll([...APP_SHELL, ...IMMUTABLE_ASSETS]);
    }).then(() => self.skipWaiting()) // activate immediately without waiting for old tabs to close
  );
});

// ── Activate: purge ALL old caches (connectx-v1 and any others) ───────────
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// ── Fetch: smart routing ───────────────────────────────────────────────────
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Pass-through: non-GET, API calls, WebSocket, cross-origin
  if (
    event.request.method !== 'GET' ||
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/ws') ||
    url.origin !== self.location.origin
  ) {
    return;
  }

  // Determine asset type
  const isImmutable = IMMUTABLE_ASSETS.some((a) => url.pathname === a);
  const isAppFile =
    url.pathname === '/' ||
    url.pathname.endsWith('.html') ||
    url.pathname.endsWith('.js') ||
    url.pathname.endsWith('.css') ||
    url.pathname.endsWith('.mjs');

  if (isImmutable) {
    // Cache-first: images, icons, manifest
    event.respondWith(
      caches.match(event.request).then((cached) => {
        if (cached) return cached;
        return fetch(event.request).then((response) => {
          if (response && response.status === 200 && response.type === 'basic') {
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, response.clone()));
          }
          return response;
        });
      })
    );
  } else if (isAppFile) {
    // Network-first: HTML, JS bundles, CSS — always fetch fresh when online
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          if (response && response.status === 200 && response.type === 'basic') {
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, response.clone()));
          }
          return response;
        })
        .catch(() => {
          // Offline fallback: serve from cache
          return caches.match(event.request).then((cached) => {
            if (cached) return cached;
            // Navigation fallback to root for SPA routing
            if (event.request.mode === 'navigate') {
              return caches.match('/');
            }
          });
        })
    );
  }
  // All other requests (fonts from Google, etc.) — pass through without caching
});

// ── Background Push Notifications ─────────────────────────────────────────
self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { title: 'ConnectX Message', body: event.data ? event.data.text() : 'New message received' };
  }

  const title = data.title || 'New Message - ConnectX';
  const options = {
    body: data.body || 'You have received a new secure message.',
    icon: data.icon || '/pwa-192x192.png',
    badge: '/pwa-192x192.png',
    vibrate: [200, 100, 200],
    data: {
      url: data.url || '/',
      conversationId: data.conversationId,
    },
    tag: data.conversationId ? `conv-${data.conversationId}` : 'connectx-msg',
    renotify: true,
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

// ── Notification Click ─────────────────────────────────────────────────────
self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const conversationId = event.notification.data ? event.notification.data.conversationId : null;
  const targetUrl = conversationId ? `/?conversation=${conversationId}` : '/';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      // Focus existing window if possible
      for (const client of clientList) {
        if ('focus' in client) {
          client.focus();
          if (conversationId && 'postMessage' in client) {
            client.postMessage({ type: 'OPEN_CONVERSATION', conversationId });
          }
          return;
        }
      }
      // No existing window — open a new one
      if (self.clients.openWindow) {
        return self.clients.openWindow(targetUrl);
      }
    })
  );
});
