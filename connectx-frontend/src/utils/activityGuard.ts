// Tracks whether the user is mid-composition (unsent draft text) or mid-send
// (upload/edit/location share in flight) anywhere in the app. The PWA update
// flow (see utils/serviceWorker.ts) reads this before reloading the page for
// a new version, so an update can never wipe an unsent draft or cut off an
// in-flight send.
type IdleListener = () => void;

let busy = false;
const idleListeners = new Set<IdleListener>();

export const activityGuard = {
  isBusy(): boolean {
    return busy;
  },

  setBusy(next: boolean): void {
    if (busy === next) return;
    busy = next;
    if (!busy) {
      idleListeners.forEach((listener) => listener());
    }
  },

  /** Fires once each time the guard transitions from busy to idle. Returns an unsubscribe fn. */
  onIdle(listener: IdleListener): () => void {
    idleListeners.add(listener);
    return () => idleListeners.delete(listener);
  },
};
