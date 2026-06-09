package com.circuitstitch.deferno.desktop.update

/**
 * Pure mapping of an [UpdateState] to the strings + action the desktop Help menu and menu-bar badge
 * render (#103). Kept a plain function (no Compose) so the per-state wording + enablement is unit tested
 * without rendering — mirroring the repo's `desktopNavKindFor` pattern.
 */
data class UpdatePresentation(
    /** "Deferno 0.1.0" — shown at the top of the Help menu (the running version, always known). */
    val versionLine: String,
    /** The primary action-row label, e.g. "Check for updates…", "Restart to update…", "Checking…". */
    val actionLabel: String,
    /** Whether the action row is clickable (false while checking/installing). */
    val actionEnabled: Boolean,
    /** What the action row does when clicked. */
    val action: UpdateAction,
    /** An optional secondary status line under the action (e.g. "You're up to date", an error). */
    val statusLine: String?,
    /** A short, always-visible menu-bar badge when an update is available/applying; null otherwise. */
    val badge: String?,
)

/** What the Help-menu primary action row triggers. */
enum class UpdateAction {
    /** Run a check ([UpdateManager.checkForUpdates]). */
    CHECK,

    /** Apply the available update ([UpdateManager.installUpdate]). */
    INSTALL,

    /** Open the GitHub Releases page in the browser (Linux / unpackaged fallback). */
    VIEW_RELEASES,

    /** Inert (no-op) — e.g. while a check is in flight. */
    NONE,
}

private const val APP_NAME = "Deferno"

/** Map [state] to its Help-menu + badge [UpdatePresentation]. Pure; unit tested per state. */
fun presentUpdate(state: UpdateState): UpdatePresentation {
    val versionLine = "$APP_NAME ${state.currentVersion}"
    return when (state) {
        is UpdateState.Idle -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "Check for updates…",
            actionEnabled = true,
            action = UpdateAction.CHECK,
            statusLine = null,
            badge = null,
        )

        is UpdateState.Checking -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "Checking for updates…",
            actionEnabled = false,
            action = UpdateAction.NONE,
            statusLine = null,
            badge = null,
        )

        is UpdateState.UpToDate -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "Check for updates…",
            actionEnabled = true,
            action = UpdateAction.CHECK,
            statusLine = "You're up to date",
            badge = null,
        )

        is UpdateState.Available -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "Restart to update to ${state.latestVersion}",
            actionEnabled = true,
            action = UpdateAction.INSTALL,
            statusLine = "Version ${state.latestVersion} is available",
            badge = "Update available",
        )

        is UpdateState.Installing -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "Updating…",
            actionEnabled = false,
            action = UpdateAction.NONE,
            statusLine = "Installing ${state.latestVersion} — the app will restart",
            badge = "Updating…",
        )

        is UpdateState.Failed -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "Check for updates…",
            actionEnabled = true,
            action = UpdateAction.CHECK,
            statusLine = "Update check failed: ${state.message}",
            badge = null,
        )

        is UpdateState.Unsupported -> UpdatePresentation(
            versionLine = versionLine,
            actionLabel = "View all releases…",
            actionEnabled = true,
            action = UpdateAction.VIEW_RELEASES,
            statusLine = when (state.reason) {
                UnsupportedReason.PACKAGE_MANAGER -> "Updates are managed by your package manager"
                UnsupportedReason.NOT_PACKAGED -> "Self-update is available in the installed app"
                UnsupportedReason.OTHER -> "Automatic updates aren't available here"
            },
            badge = null,
        )
    }
}
