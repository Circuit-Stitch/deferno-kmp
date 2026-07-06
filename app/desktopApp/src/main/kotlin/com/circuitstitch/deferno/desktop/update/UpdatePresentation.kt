package com.circuitstitch.deferno.desktop.update

/**
 * Pure mapping of an [UpdateState] to the typed tokens + action the desktop Help menu and menu-bar badge
 * render (#103). Kept a plain function (no Compose) so the per-state selection + enablement is unit tested
 * without rendering — mirroring the repo's `desktopNavKindFor` pattern. The View resolves each
 * [UpdateStringKey] to a localized `update_*` string (#325); [versionArg] fills the `%1$s` of the
 * arg-bearing keys (Available/Installing).
 */
data class UpdatePresentation(
    /** The running app version — the View composes the "Deferno 0.1.0" line from it. */
    val currentVersion: String,
    /** The primary action-row label key (Check / Checking / Restart to update / Updating / View releases). */
    val actionLabel: UpdateStringKey,
    /** Whether the action row is clickable (false while checking/installing). */
    val actionEnabled: Boolean,
    /** What the action row does when clicked. */
    val action: UpdateAction,
    /** Optional secondary status line under the action (up to date / available / installing / error / unsupported). */
    val statusLine: UpdateStringKey?,
    /** A short, always-visible menu-bar badge when an update is available/applying; null otherwise. */
    val badge: UpdateStringKey?,
    /** The latest version, when [actionLabel]/[statusLine] interpolate it; null otherwise. */
    val versionArg: String? = null,
)

/** A localizable Help-menu/badge line; the View maps each to its `update_*` string resource (#325). */
enum class UpdateStringKey {
    CHECK, // update_check_action
    CHECKING, // update_checking
    RESTART_TO_UPDATE, // update_restart_to_update (%1$s = latest)
    UPDATING, // update_updating
    VIEW_RELEASES, // update_view_releases
    UP_TO_DATE, // update_up_to_date
    VERSION_AVAILABLE, // update_version_available (%1$s = latest)
    INSTALLING, // update_installing_status (%1$s = latest)
    CHECK_FAILED, // update_check_failed_fallback
    UNSUPPORTED_PACKAGE_MANAGER, // update_unsupported_package_manager
    UNSUPPORTED_NOT_PACKAGED, // update_unsupported_not_packaged
    UNSUPPORTED_OTHER, // update_unsupported_other
    BADGE_AVAILABLE, // update_badge_available
    BADGE_UPDATING, // update_updating
}

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

/** Map [state] to its Help-menu + badge [UpdatePresentation]. Pure; unit tested per state. */
fun presentUpdate(state: UpdateState): UpdatePresentation = when (state) {
    is UpdateState.Idle -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.CHECK,
        actionEnabled = true,
        action = UpdateAction.CHECK,
        statusLine = null,
        badge = null,
    )

    is UpdateState.Checking -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.CHECKING,
        actionEnabled = false,
        action = UpdateAction.NONE,
        statusLine = null,
        badge = null,
    )

    is UpdateState.UpToDate -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.CHECK,
        actionEnabled = true,
        action = UpdateAction.CHECK,
        statusLine = UpdateStringKey.UP_TO_DATE,
        badge = null,
    )

    is UpdateState.Available -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.RESTART_TO_UPDATE,
        actionEnabled = true,
        action = UpdateAction.INSTALL,
        statusLine = UpdateStringKey.VERSION_AVAILABLE,
        badge = UpdateStringKey.BADGE_AVAILABLE,
        versionArg = state.latestVersion,
    )

    is UpdateState.Installing -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.UPDATING,
        actionEnabled = false,
        action = UpdateAction.NONE,
        statusLine = UpdateStringKey.INSTALLING,
        badge = UpdateStringKey.BADGE_UPDATING,
        versionArg = state.latestVersion,
    )

    is UpdateState.Failed -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.CHECK,
        actionEnabled = true,
        action = UpdateAction.CHECK,
        statusLine = UpdateStringKey.CHECK_FAILED,
        badge = null,
    )

    is UpdateState.Unsupported -> UpdatePresentation(
        currentVersion = state.currentVersion,
        actionLabel = UpdateStringKey.VIEW_RELEASES,
        actionEnabled = true,
        action = UpdateAction.VIEW_RELEASES,
        statusLine = when (state.reason) {
            UnsupportedReason.PACKAGE_MANAGER -> UpdateStringKey.UNSUPPORTED_PACKAGE_MANAGER
            UnsupportedReason.NOT_PACKAGED -> UpdateStringKey.UNSUPPORTED_NOT_PACKAGED
            UnsupportedReason.OTHER -> UpdateStringKey.UNSUPPORTED_OTHER
        },
        badge = null,
    )
}
