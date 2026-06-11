package com.circuitstitch.deferno.desktop.chrome

/**
 * A recording [ChromeBackend] (#117 tests): captures the installed app-menu handlers so a test can
 * fire them, and every badge write so a test can assert the sequence. [appMenuSupported] = false
 * models a desktop with no native app menu (Linux/Windows); [badgeSupported] = false one that
 * can't badge its dock icon.
 */
class FakeChromeBackend(
    private val appMenuSupported: Boolean = true,
    override val badgeSupported: Boolean = true,
) : ChromeBackend {
    var aboutHandler: (() -> Unit)? = null
    var preferencesHandler: (() -> Unit)? = null
    var quitHandler: (() -> Unit)? = null
    val badges = mutableListOf<String?>()

    override fun installAboutHandler(onAbout: () -> Unit): Boolean {
        if (appMenuSupported) aboutHandler = onAbout
        return appMenuSupported
    }

    override fun installPreferencesHandler(onPreferences: () -> Unit): Boolean {
        if (appMenuSupported) preferencesHandler = onPreferences
        return appMenuSupported
    }

    override fun installQuitHandler(onQuit: () -> Unit): Boolean {
        if (appMenuSupported) quitHandler = onQuit
        return appMenuSupported
    }

    override fun setBadge(text: String?) {
        badges += text
    }
}
