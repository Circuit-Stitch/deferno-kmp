package com.circuitstitch.deferno.desktop.chrome

import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.RootComponent
import software.amazon.app.kmplogger.Logger

/**
 * Wire the OS-native app menu into the app (#117): About / Preferences / Quit land in the same
 * intents the in-app menu bar drives, which stays as the cross-platform fallback (on macOS both
 * surfaces exist; they route to the same handlers, so the duplication is harmless). Where the
 * desktop has no native app menu (Linux/Windows — every install reports `false`) this is a no-op.
 */
fun installAppMenuHandlers(
    backend: ChromeBackend,
    onAbout: () -> Unit,
    onPreferences: () -> Unit,
    onQuit: () -> Unit,
) {
    val about = backend.installAboutHandler(onAbout)
    val preferences = backend.installPreferencesHandler(onPreferences)
    val quit = backend.installQuitHandler(onQuit)
    Logger("DesktopChrome").i {
        "OS-native chrome: about=$about preferences=$preferences quit=$quit badge=${backend.badgeSupported}"
    }
}

/**
 * The Preferences (⌘,) app-menu routing (#117): switch the active Main shell to the Settings
 * Destination — lateral and state-preserving, like any nav-rail tap. A no-op in the Auth shell:
 * Settings are per-Account, so pre-Account there is nothing to open.
 */
fun openPreferences(root: RootComponent) {
    root.activeMainShell()?.selectDestination(Destination.Settings)
}

/** The Main shell when it is the active child, else `null` (the Auth shell has no Destinations). */
internal fun RootComponent.activeMainShell(): MainShellComponent? =
    (stack.value.active.instance as? RootComponent.Child.Main)?.component
