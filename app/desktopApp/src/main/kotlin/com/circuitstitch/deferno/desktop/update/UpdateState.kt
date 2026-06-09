package com.circuitstitch.deferno.desktop.update

/**
 * The user-facing self-update state surfaced by the desktop in-app "Check for updates" affordance
 * (#103, ADR-0021). [UpdateManager] drives it over Conveyor's update engine; the Help menu + the
 * menu-bar badge render it. Every state carries [currentVersion] (the running app's version) so the UI
 * can always show it.
 *
 * Self-update is **Windows + macOS only** (ADR-0021): on Linux the package manager updates the app, and
 * an unpackaged dev run can't self-update at all — both surface as [Unsupported].
 */
sealed interface UpdateState {
    val currentVersion: String

    /** Self-update isn't available in this build; [reason] tells the UI what to say instead. */
    data class Unsupported(
        override val currentVersion: String,
        val reason: UnsupportedReason,
    ) : UpdateState

    /** Packaged on a self-updating OS (Win/Mac); no check has run yet. */
    data class Idle(override val currentVersion: String) : UpdateState

    /** A check is in flight. */
    data class Checking(override val currentVersion: String) : UpdateState

    /** The last check found the running version is current. */
    data class UpToDate(override val currentVersion: String) : UpdateState

    /** A newer [latestVersion] is published and can be installed (applied on restart). */
    data class Available(
        override val currentVersion: String,
        val latestVersion: String,
    ) : UpdateState

    /** The update is being applied — the OS updater has taken over and the app will restart. */
    data class Installing(
        override val currentVersion: String,
        val latestVersion: String,
    ) : UpdateState

    /** The last check failed (e.g. offline); [message] is a short reason. */
    data class Failed(
        override val currentVersion: String,
        val message: String,
    ) : UpdateState
}

/** Why self-update is [UpdateState.Unsupported] — selects the Help-menu message. */
enum class UnsupportedReason {
    /** Not launched from a Conveyor package (e.g. `./gradlew run`) — no updater present. */
    NOT_PACKAGED,

    /** Linux: in-app self-update is off by design; the distro package manager updates the app (#105). */
    PACKAGE_MANAGER,

    /** Conveyor reports the updater is otherwise unavailable here. */
    OTHER,
}
