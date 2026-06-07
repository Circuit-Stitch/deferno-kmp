package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value

/**
 * The auth-state seam the [RootComponent] reads to choose its [[Shell]] (ADR-0013): when there is an
 * [[Active Account]] the Main shell is shown, otherwise the Auth shell. It is **scene-scoped, not a
 * process global** (ADR-0008 G3) — a future second window can hold a different gate / Active Account —
 * so each scene's [RootComponent] is handed its own gate.
 *
 * STUB until #15 (sign-in / MFA / account-picker) lands: [signIn]/[signOut] just flip an in-memory
 * flag so the shell boundary is real and exercisable now. The real gate will observe the
 * `AccountManager`'s Active Account (ADR-0002) instead.
 */
interface AuthGate {
    /** `true` once an Active Account is present — drives Main vs Auth shell. */
    val signedIn: Value<Boolean>

    /** Enter the Main shell (stub for completing the Auth flow). */
    fun signIn()

    /** Drop back to the Auth shell — fast user switching / add-account is re-entrant (ADR-0013). */
    fun signOut()
}

/**
 * In-memory [AuthGate] for the pre-DI shell. Defaults to **signed-out** so the app opens into the Auth
 * shell and the shell boundary is self-demonstrating: "Continue" ([signIn]) crosses into the Main
 * shell (which opens into the Plan), and [signOut] returns — the re-entrancy ADR-0013 describes.
 */
class StubAuthGate(initiallySignedIn: Boolean = false) : AuthGate {
    private val _signedIn = MutableValue(initiallySignedIn)
    override val signedIn: Value<Boolean> = _signedIn

    override fun signIn() {
        _signedIn.value = true
    }

    override fun signOut() {
        _signedIn.value = false
    }
}
