import { groupKeyManager } from './groupKeyManager';
import { Group } from '../types';

/**
 * GROUP E2EE media only. Shared by ImageMessageContent/DocumentMessageContent so both leaf
 * components resolve a media file's group key the exact same way the App.tsx TEXT/caption decrypt
 * pipeline already does for `msg.groupKeyVersion` (see decryptSingleMessage's own comment) --
 * current version -> passive resolveGroupKey (cache -> fetch/unwrap own row, never mints);
 * strictly historical version -> getKeyForVersion (cache only, never fetches or mints). A media
 * message with no caption never runs through that TEXT pipeline at all (nothing to decrypt there),
 * so this is the one place that resolution has to happen independently for the file itself.
 */
export async function resolveGroupMediaKey(
  group: Group,
  mediaKeyVersion: number,
  currentUserId: number
): Promise<CryptoKey | null> {
  if (group.keyVersion <= mediaKeyVersion) {
    return groupKeyManager.resolveGroupKey(group, currentUserId);
  }
  return groupKeyManager.getKeyForVersion(group.id, mediaKeyVersion);
}
