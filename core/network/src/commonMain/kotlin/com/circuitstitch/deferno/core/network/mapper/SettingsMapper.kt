package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.network.dto.UserSettingsDto

/**
 * The DTO ⇄ domain mapping for `/auth/me/settings` (#72) — the "condense at the edge" boundary of
 * ADR-0011. The faithful, all-nullable wire shape ([UserSettingsDto], snake_case keys, `"deferno"`/
 * `"light"` string tokens) stays quarantined in `core:network`; everything above sees only the clean
 * `core:model` [UserSettings] with its [ThemeFamily]/[ThemeMode] enums.
 *
 * **Tolerant read (ADR-0005/0011).** Settings is the payload most likely to gain or drop keys, so a
 * `null`/unknown theme token degrades to the **default** ([ThemeFamily.Deferno] / [ThemeMode.Auto])
 * rather than failing — the same defensive degrade the wire enums use for additive tokens.
 *
 * **Write tokens.** [ThemeFamily.toWireToken] / [ThemeMode.toWireToken] are the inverse used by the
 * offline outbox's settings PATCH bodies (`core:data`), so the wire casing lives only here (#23).
 */
fun UserSettingsDto.toDomain(): UserSettings = UserSettings(
    themeFamily = themeFamily.toThemeFamily(),
    themeMode = themeMode.toThemeMode(),
    globalDoneVisibilitySeconds = globalDoneVisibilitySeconds,
    dashboardDoneVisibilitySeconds = dashboardDoneVisibilitySeconds,
    timeZone = timeZone,
    trackingEnabled = trackingEnabled,
    dragAndDropEnabled = dragAndDropEnabled,
)

/** `theme_family` token → [ThemeFamily]; `null`/unknown degrades to the default [ThemeFamily.Deferno]. */
fun String?.toThemeFamily(): ThemeFamily = when (this) {
    "deferno" -> ThemeFamily.Deferno
    "mono" -> ThemeFamily.Mono
    else -> ThemeFamily.Deferno
}

/** `theme_mode` token → [ThemeMode]; `null`/unknown degrades to the default [ThemeMode.Auto]. */
fun String?.toThemeMode(): ThemeMode = when (this) {
    "light" -> ThemeMode.Light
    "dark" -> ThemeMode.Dark
    "auto" -> ThemeMode.Auto
    else -> ThemeMode.Auto
}

/** [ThemeFamily] → its wire `theme_family` token — the write direction (ADR-0011). */
fun ThemeFamily.toWireToken(): String = when (this) {
    ThemeFamily.Deferno -> "deferno"
    ThemeFamily.Mono -> "mono"
}

/** [ThemeMode] → its wire `theme_mode` token — the write direction (ADR-0011). */
fun ThemeMode.toWireToken(): String = when (this) {
    ThemeMode.Light -> "light"
    ThemeMode.Dark -> "dark"
    ThemeMode.Auto -> "auto"
}
