package com.circuitstitch.deferno.desktop.chrome

import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Taskbar

/**
 * The AWT seam for the OS-native window/menu chrome (#117): the macOS app-menu items (About /
 * Preferences / Quit) and the dock-icon badge. Production is [AwtChromeBackend] over
 * [java.awt.Desktop] + [java.awt.Taskbar]; tests fake this interface, so the chrome *logic*
 * ([installAppMenuHandlers], [PlanBadge]) stays unit-testable on the Linux fast path.
 *
 * Every operation is **capability-guarded, not OS-named**: a desktop that doesn't offer the action
 * (Linux/Windows have no native app menu; a headless host has no Desktop at all) reports `false` /
 * no-ops instead of throwing. In practice the app-menu trio is macOS-only and the badge is macOS
 * (text) plus the odd Linux dock (number), but the guard is the JDK's own `isSupported(...)`, so
 * any platform that grows support gets it for free.
 */
interface ChromeBackend {
    /** Install the native About app-menu handler; `false` where there is no native About item. */
    fun installAboutHandler(onAbout: () -> Unit): Boolean

    /** Install the native Preferences (⌘,) app-menu handler; `false` where unsupported. */
    fun installPreferencesHandler(onPreferences: () -> Unit): Boolean

    /**
     * Intercept the native Quit (⌘Q / Dock → Quit / app menu → Quit) and route it into [onQuit] —
     * the app's graceful exit — instead of AWT's default `System.exit`. `false` where quit isn't
     * interceptable (there, window close remains the only exit path, as before #117).
     */
    fun installQuitHandler(onQuit: () -> Unit): Boolean

    /** Whether this desktop can badge the app's dock/taskbar icon (gates [PlanBadge]'s collector). */
    val badgeSupported: Boolean

    /** Show [text] on the dock icon, or clear the badge when `null`. No-op where unsupported. */
    fun setBadge(text: String?)
}

/** The real AWT [ChromeBackend] (#117) — see the interface for the capability-guard contract. */
object AwtChromeBackend : ChromeBackend {

    override fun installAboutHandler(onAbout: () -> Unit): Boolean =
        installHandler(Desktop.Action.APP_ABOUT) { desktop ->
            desktop.setAboutHandler { onEdt(onAbout) }
        }

    override fun installPreferencesHandler(onPreferences: () -> Unit): Boolean =
        installHandler(Desktop.Action.APP_PREFERENCES) { desktop ->
            desktop.setPreferencesHandler { onEdt(onPreferences) }
        }

    override fun installQuitHandler(onQuit: () -> Unit): Boolean =
        installHandler(Desktop.Action.APP_QUIT_HANDLER) { desktop ->
            desktop.setQuitHandler { _, response ->
                // Route into the app's graceful exit (Compose teardown ends the JVM) and CANCEL the
                // native quit — performQuit() would System.exit() immediately, racing that teardown.
                onEdt(onQuit)
                response.cancelQuit()
            }
        }

    // macOS offers ICON_BADGE_TEXT; some Linux docks offer ICON_BADGE_NUMBER (fine here — the badge
    // is always a count). Windows offers neither for text (image-only), so it reports unsupported.
    // Queried per call (not cached at class-load) so it reflects the initialized toolkit.
    override val badgeSupported: Boolean
        get() = runCatching {
            Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().let { taskbar ->
                taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT) ||
                    taskbar.isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)
            }
        }.getOrDefault(false)

    override fun setBadge(text: String?) {
        if (!badgeSupported) return
        // The badge collector runs off a background scope; touch AWT only on the EDT.
        EventQueue.invokeLater {
            runCatching { Taskbar.getTaskbar().setIconBadge(text) }
        }
    }

    /** Install [install] when [action] is supported here; `false` (and never a throw) otherwise. */
    private fun installHandler(action: Desktop.Action, install: (Desktop) -> Unit): Boolean =
        runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(action)) return@runCatching false
            install(desktop)
            true
        }.getOrDefault(false)

    /**
     * Run an app-menu action on the EDT. AWT promises no particular thread for these callbacks, and
     * everything they route into (Decompose navigation, Compose snapshot state, exitApplication)
     * expects the UI thread — which on Compose Desktop is the EDT.
     */
    private fun onEdt(action: () -> Unit) = EventQueue.invokeLater(action)
}
