package com.connectx.user.entity;

/**
 * users.group_add_privacy -- "Who can add me to groups?" (docs/CONNECTX_GROUP_ARCHITECTURE.md §8).
 * NULL on the entity/column means ANYONE (see User#getGroupAddPrivacy's javadoc and
 * GroupAuthorizationService#resolveGroupAddPrivacy, the single place that interpretation lives) --
 * mirrors profilePhotoVisibility's null-means-EVERYONE convention on the same entity.
 * <p>
 * ANYONE does not mean "any random person can force this user into any group" -- it only means
 * this user's own preference does not additionally restrict an otherwise-authorized action; group
 * role/policy and blocking still apply on top (see GroupAuthorizationService#evaluateAddMember).
 * CONNECTIONS is a target-side veto stronger than group role: only a user actually connected to
 * this one may add/invite them at all, even an OWNER/ADMIN who could otherwise invite anyone.
 * NOBODY blocks direct addition/invitation entirely; it never blocks the user's own ability to
 * leave, nor (per Stage 1.5/2/3's consent rule, unaffected by this setting) does any value here
 * ever silently restore a previously LEFT/REMOVED membership -- that still always requires a
 * fresh invitation and explicit acceptance.
 */
public enum GroupAddPrivacy {
    ANYONE,
    CONNECTIONS,
    NOBODY
}
