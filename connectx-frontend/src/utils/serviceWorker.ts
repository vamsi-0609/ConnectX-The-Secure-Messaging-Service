import { activityGuard } from './activityGuard';

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
    watchForUpdates(registration);
    return registration;
  } catch (error) {
    console.warn('[ConnectX SW] Service Worker registration failed:', error);
    return null;
  }
}

export function getServiceWorkerRegistration(): ServiceWorkerRegistration | null {
  return swRegistration;
}

// ── Update lifecycle ────────────────────────────────────────────────────────
// A new sw.js is pre-cached in the background as soon as the browser finds a
// byte diff (see public/sw.js's `install` handler) and then parked in the
// "waiting" state — it deliberately does not self-activate. This module's job
// is only to pick the moment to let it take over:
//   1. Notice a waiting worker (updatefound / already-waiting on load).
//   2. Tell it to activate (postMessage 'SKIP_WAITING') the instant it's safe
//      — i.e. activityGuard reports no unsent draft / in-flight send.
//   3. Reload exactly once, driven by `controllerchange`, so the page that
//      reloads is actually served by the worker that just took over.
// The currently running tab is never touched before that point: the old
// worker keeps serving it untouched, so there's no white screen and no risk
// of interrupting composition.
function watchForUpdates(registration: ServiceWorkerRegistration): void {
  let updateApplied = false;
  let reloadTriggered = false;

  navigator.serviceWorker.addEventListener('controllerchange', () => {
    // Only reload if THIS tab is the one that asked for the swap — this also
    // guards against reloading on the very first-ever SW activation, where
    // the controller goes from null -> active but there's nothing new to load.
    if (reloadTriggered || !updateApplied) return;
    reloadTriggered = true;
    window.location.reload();
  });

  const onWaitingWorker = (worker: ServiceWorker) => {
    if (updateApplied) return;

    const tryApply = () => {
      if (updateApplied || activityGuard.isBusy()) return;
      updateApplied = true;
      worker.postMessage('SKIP_WAITING');
    };

    tryApply();
    if (!updateApplied) {
      const unsubscribe = activityGuard.onIdle(() => {
        tryApply();
        if (updateApplied) unsubscribe();
      });
    }
  };

  // A worker may already be sitting in "waiting" from before this tab's JS
  // finished initializing (e.g. the browser installed it very quickly).
  if (registration.waiting && navigator.serviceWorker.controller) {
    onWaitingWorker(registration.waiting);
  }

  registration.addEventListener('updatefound', () => {
    const installing = registration.installing;
    if (!installing) return;
    installing.addEventListener('statechange', () => {
      // `controller` truthy means an old worker was already active — i.e.
      // this is a genuine update, not this page's first-ever SW install.
      if (installing.state === 'installed' && navigator.serviceWorker.controller) {
        onWaitingWorker(installing);
      }
    });
  });

  // Force an immediate, explicit version check now (bypasses browsers'
  // ~24h automatic throttle) and again each time the tab is foregrounded, so
  // a PWA left open for days across a deploy still notices it promptly. This
  // is intentionally event-driven (page load, tab foregrounded) rather than a
  // timer/poll: `registration.update()` only re-fetches the small sw.js
  // script itself, and if its bytes are unchanged the browser stops right
  // there — no reinstall, no re-caching, no extra asset downloads. So these
  // checks stay cheap and never turn into a request loop even though nothing
  // changed most of the time.
  registration.update().catch(() => {});
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      registration.update().catch(() => {});
    }
  });
}
