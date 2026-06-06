package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `GET /auth/me/settings` payload — the user's preference bag (theme, done-visibility windows,
 * time zone, feature toggles). A faithful flat wire DTO decoded by the tolerant reader
 * ([com.circuitstitch.deferno.core.network.DefernoJson]); must parse `contracts/fixtures/settings.json`
 * (#19).
 *
 * Every field is nullable/defaulted: settings is the payload most likely to gain or drop keys over
 * time, so the tolerant reader degrades a missing field to its default rather than failing. The
 * domain mapping and the intent-shaped PATCH body (CONTRACT-NOTES → "Mutations", #23) earn their own
 * issue — this is the lossless read shape the harness asserts against.
 */
@Serializable
data class UserSettingsDto(
    @SerialName("global_done_visibility_seconds") val globalDoneVisibilitySeconds: Long? = null,
    @SerialName("dashboard_done_visibility_seconds") val dashboardDoneVisibilitySeconds: Long? = null,
    @SerialName("theme_family") val themeFamily: String? = null,
    @SerialName("theme_mode") val themeMode: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    @SerialName("tracking_enabled") val trackingEnabled: Boolean = false,
    @SerialName("drag_and_drop_enabled") val dragAndDropEnabled: Boolean = false,
)
