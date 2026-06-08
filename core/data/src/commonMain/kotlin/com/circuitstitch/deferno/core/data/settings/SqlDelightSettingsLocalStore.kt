package com.circuitstitch.deferno.core.data.settings

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.UserSettingsEntity
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The production [SettingsLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #72). Maps the
 * singleton `userSettingsEntity` row to/from the domain [UserSettings]. The enum columns store the
 * DOMAIN enum names (`Deferno`/`Auto`, …) — the wire casing lives only in `core:network` (ADR-0011) —
 * so they round-trip via `enumValueOf`, defensively degrading an unrecognised stored value to the
 * default (so an older/corrupt row never crashes the reader).
 *
 * The observe [dispatcher] is injected (default [Dispatchers.Default]) so a test can run the Flow on
 * its own scheduler.
 */
class SqlDelightSettingsLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SettingsLocalStore {

    private val queries get() = db.userSettingsEntityQueries

    override fun observeSettings(): Flow<UserSettings?> =
        queries.selectSettings()
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { row -> row?.toDomain() }

    override suspend fun currentSettings(): UserSettings? =
        queries.selectSettings().executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(settings: UserSettings) {
        queries.upsertSettings(
            theme_family = settings.themeFamily.name,
            theme_mode = settings.themeMode.name,
            global_done_visibility_seconds = settings.globalDoneVisibilitySeconds,
            dashboard_done_visibility_seconds = settings.dashboardDoneVisibilitySeconds,
            time_zone = settings.timeZone,
            tracking_enabled = if (settings.trackingEnabled) 1L else 0L,
            drag_and_drop_enabled = if (settings.dragAndDropEnabled) 1L else 0L,
            username = settings.username,
        )
    }
}

/** Row → domain, degrading an unrecognised enum token to the default (ADR-0011 tolerant read). */
private fun UserSettingsEntity.toDomain(): UserSettings = UserSettings(
    themeFamily = theme_family.toThemeFamilyOrDefault(),
    themeMode = theme_mode.toThemeModeOrDefault(),
    globalDoneVisibilitySeconds = global_done_visibility_seconds,
    dashboardDoneVisibilitySeconds = dashboard_done_visibility_seconds,
    timeZone = time_zone,
    trackingEnabled = tracking_enabled != 0L,
    dragAndDropEnabled = drag_and_drop_enabled != 0L,
    username = username,
)

private fun String.toThemeFamilyOrDefault(): ThemeFamily =
    ThemeFamily.entries.firstOrNull { it.name == this } ?: ThemeFamily.Deferno

private fun String.toThemeModeOrDefault(): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.Auto
