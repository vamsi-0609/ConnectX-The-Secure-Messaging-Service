/* ConnectX Service Worker v2 — Smart Cache + Background Push Notifications + Safe Auto-Update */

// Stamped at build time (see vite.config.ts's stampServiceWorkerVersion plugin)
// with a hash of that build's actual output filenames. This is the ONLY line
// that changes between deploys — and deliberately so: browsers detect a new
// Service Worker purely by byte-diffing this exact file, so without a marker
// that changes per build, a normal app-only deploy (new React code, this
// script otherwise untouched) would be byte-identical to the previous
// version and no update would ever be detected. Deriving it from the
// content-hashed output filenames (which Vite already only changes when the
// build output actually changes) also means a redundant CI rebuild with no
// real changes produces the same version — no spurious update either.
const BUILD_VERSION = '__BUILD_VERSION__';

// Cache name is deploy-scoped so `activate` below automatically evicts the
// previous version's cached app shell/JS/CSS the moment a new build takes
// over — no manually-maintained version number to remember to bump.
const CACHE_NAME = 'connectx-v2-' + BUILD_VERSION;

// Holds at most one pending Web Share Target payload (files + metadata) between
// the OS share hand-off and the app reading it on next load. Kept out of
// CACHE_NAME so a routine app-version bump (see activate below) can't wipe an
// in-flight share before the client has a chance to consume it.
const SHARE_CACHE_NAME = 'connectx-share-target-v1';
const SHARE_MANIFEST_URL = '/__share-target-manifest';

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
// Deliberately does NOT call self.skipWaiting() here. A newly-installed worker
// downloads/caches its assets in the background and then sits in the
// "waiting" state — behind the still-active old worker — until this tab's
// client code (see src/utils/serviceWorker.ts) explicitly tells it to take
// over via a SKIP_WAITING message, which it only sends once nothing is
// mid-composition or mid-send. This is what makes updates safe: the running
// (old) version keeps serving the current page, untouched, until that signal.
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll([...APP_SHELL, ...IMMUTABLE_ASSETS]);
    })
  );
});

// ── Activate: purge ALL old caches (connectx-v1 and any others) ───────────
self.addEventListener('activate', (event) => {
  const keepCaches = new Set([CACHE_NAME, SHARE_CACHE_NAME]);
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => !keepCaches.has(k)).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// ── Update handshake: activate only when the page says it's safe ──────────
self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

// ── Web Share Target: OS "Share" hand-off (images/files → the send flow) ──
// The browser POSTs the shared files here as a real top-level navigation
// (see manifest.json's share_target). Static hosting has no server route for
// this, so the SW fully owns the request: stash the files in Cache Storage,
// then redirect to a plain GET the SPA can load normally. The app reads the
// cache on startup and hands the files to the existing media-send flow —
// same upload/encryption path as a manual file pick.
async function handleShareTarget(event) {
  const cache = await caches.open(SHARE_CACHE_NAME);
  // Drop any previous unconsumed share so state never leaks between shares.
  const staleKeys = await cache.keys();
  await Promise.all(staleKeys.map((req) => cache.delete(req)));

  try {
    const formData = await event.request.formData();
    const files = formData
      .getAll('media')
      .filter((entry) => entry instanceof File && entry.size > 0);

    const manifest = {
      receivedAt: Date.now(),
      files: files.map((file, index) => ({
        url: `/__share-target-file-${index}`,
        name: file.name || `shared-file-${index}`,
        type: file.type || 'application/octet-stream',
      })),
    };

    await Promise.all(
      manifest.files.map((entry, index) =>
        cache.put(entry.url, new Response(files[index], { headers: { 'Content-Type': entry.type } }))
      )
    );
    await cache.put(SHARE_MANIFEST_URL, new Response(JSON.stringify(manifest)));
  } catch (err) {
    console.error('[ConnectX SW] Failed to process shared files:', err);
  }

  return Response.redirect(new URL('/?share-target=1', self.location.origin).href, 303);
}

// ── Fetch: smart routing ───────────────────────────────────────────────────
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (event.request.method === 'POST' && url.pathname === '/share-target/') {
    event.respondWith(handleShareTarget(event));
    return;
  }

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
