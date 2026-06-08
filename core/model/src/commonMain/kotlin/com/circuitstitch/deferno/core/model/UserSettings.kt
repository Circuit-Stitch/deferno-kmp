package com.circuitstitch.deferno.core.model

/**
 * The two brand theme families a user can pick (ADR-0010). The wire tokens (`"deferno"`/`"mono"`)
 * stay quarantined in the `core:network` mapper; the domain carries only this clean enum.
 */
enum class ThemeFamily { Deferno, Mono }

/**
 * How the app resolves light vs dark (#72). [Auto] follows the OS, so the boolean the theme layer
 * needs is resolved at render time against the live system-dark signal ([ThemeMode.resolveDark]).
 * Wire tokens (`"light"`/`"dark"`/`"auto"`) stay in the mapper.
 */
enum class ThemeMode {
    Light,
    Dark,
    Auto,
    ;

    /** Resolve to the boolean [com.circuitstitch.deferno.core.designsystem] needs: [Auto] defers to [systemDark]. */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        Light -> false
        Dark -> true
        Auto -> systemDark
    }
}

/**
 * The user's preference bag — the domain model of `GET/PATCH /auth/me/settings` (#72), condensed at
 * the `core:network` boundary from `UserSettingsDto` (ADR-0011). Everything above the network layer
 * sees only this clean shape, never the snake_case wire DTO.
 *
 * It backs the Settings Destination's functional categories (#72): **Appearance** ([themeFamily] +
 * [themeMode], applied live), **Task behavior** ([globalDoneVisibilitySeconds] /
 * [dashboardDoneVisibilitySeconds] windows + the experimental [dragAndDropEnabled] toggle),
 * **Data & Privacy** ([trackingEnabled]), and **Account** ([username] + [timeZone]).
 *
 * A [Default] instance seeds the observable settings StateFlow before the first refresh lands, so the
 * theme always has a value (offline-first, ADR-0001) — including in the no-account / Auth-shell state.
 */
data class UserSettings(
    val themeFamily: ThemeFamily = ThemeFamily.Deferno,
    val themeMode: ThemeMode = ThemeMode.Auto,
    val globalDoneVisibilitySeconds: Long? = null,
    val dashboardDoneVisibilitySeconds: Long? = null,
    val timeZone: String? = null,
    val trackingEnabled: Boolean = false,
    val dragAndDropEnabled: Boolean = false,
    /**
     * The login handle, when the settings payload carried it (the read DTO doesn't yet — left null);
     * the Account category renders identity from `/auth/me`'s [User] when present.
     */
    val username: String? = null,
) {
    companion object {
        /** The seeded default: Deferno palette, OS-following mode, no toggles — a sane pre-refresh value. */
        val Default: UserSettings = UserSettings()
    }
}
