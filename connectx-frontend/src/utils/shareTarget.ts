// Client-side counterpart to the Web Share Target handling in public/sw.js.
// The SW stashes shared files in Cache Storage and redirects the OS "Share"
// hand-off to `/?share-target=1`; this module reads that cache once on
// startup and hands the files to the app's existing media-send flow, so
// sharing never bypasses the normal upload/encryption pipeline.
import { MEDIA_IMAGE_ACCEPT } from './mediaImage';

// Must match SHARE_CACHE_NAME / SHARE_MANIFEST_URL in public/sw.js — the two
// files can't share a literal since the SW is plain, unbundled JS.
const SHARE_CACHE_NAME = 'connectx-share-target-v1';
const SHARE_MANIFEST_URL = '/__share-target-manifest';

export interface PendingShare {
  files: File[];
}

interface ShareManifestEntry {
  url: string;
  name: string;
  type: string;
}

interface ShareManifest {
  receivedAt: number;
  files: ShareManifestEntry[];
}

/** True when the current URL is the post-redirect landing from a share hand-off. */
export function hasPendingShareMarker(): boolean {
  return new URLSearchParams(window.location.search).has('share-target');
}

/** Removes the `share-target` marker so a page refresh doesn't re-trigger consumption. */
export function stripShareTargetParam(): void {
  const url = new URL(window.location.href);
  url.searchParams.delete('share-target');
  window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
}

/**
 * Reads and clears the pending share left by the service worker, reconstructing
 * File objects from the cached blobs. Returns null if unsupported, absent, or empty.
 */
export async function consumePendingShare(): Promise<PendingShare | null> {
  if (typeof caches === 'undefined') return null;

  try {
    const cache = await caches.open(SHARE_CACHE_NAME);
    const manifestResponse = await cache.match(SHARE_MANIFEST_URL);
    if (!manifestResponse) return null;

    const manifest = (await manifestResponse.json()) as ShareManifest;
    const files: File[] = [];
    for (const entry of manifest.files) {
      const fileResponse = await cache.match(entry.url);
      if (!fileResponse) continue;
      const blob = await fileResponse.blob();
      files.push(new File([blob], entry.name, { type: entry.type }));
    }

    const keys = await cache.keys();
    await Promise.all(keys.map((req) => cache.delete(req)));

    return files.length > 0 ? { files } : null;
  } catch (err) {
    console.warn('[ConnectX] Failed to read shared files:', err);
    return null;
  }
}

const SHARED_IMAGE_MIME_TYPES = new Set(MEDIA_IMAGE_ACCEPT.split(','));

/** Categorizes shared files the same way the manual Image vs. Document pickers do. */
export function splitSharedFiles(files: File[]): { images: File[]; docs: File[] } {
  const images: File[] = [];
  const docs: File[] = [];
  for (const file of files) {
    if (SHARED_IMAGE_MIME_TYPES.has(file.type)) {
      images.push(file);
    } else {
      docs.push(file);
    }
  }
  return { images, docs };
}
