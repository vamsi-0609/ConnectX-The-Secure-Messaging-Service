package com.connectx.app.core.session

/**
 * The three states N3.0 approved for local session tracking -- deliberately no
 * `Loading`/`Refreshing`/`Error`/`LoggingIn` states here; those belong to later
 * authentication/UI layers (N3.3 refresh handling, N3.5 auth screens), not this
 * foundational session boundary.
 */
sealed interface SessionState {

    /** Not yet determined -- before [com.connectx.app.core.session.SessionManager.restoreSession] has run. */
    data object Unknown : SessionState

    /** A complete token pair is present in [com.connectx.app.data.local.auth.TokenStorage]. */
    data object Authenticated : SessionState

    /** No tokens, or an incomplete pair (already cleared) are present. */
    data object Unauthenticated : SessionState
}
