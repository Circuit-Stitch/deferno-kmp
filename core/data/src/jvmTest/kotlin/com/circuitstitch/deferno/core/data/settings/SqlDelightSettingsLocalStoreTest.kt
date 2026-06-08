package com.circuitstitch.deferno.core.data.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The real-SQLite integration test for the settings path (#72, ADR-0006 JVM-fast path). Proves
 * [SqlDelightSettingsLocalStore]'s singleton-row upsert + observe round-trip through a genuine
 * `DefernoDatabase`: an empty store observes `null`, an upsert is observed as the domain settings, a
 * second upsert REPLACES the same single row (the `CHECK (id = 0)` singleton), and the enum columns
 * round-trip through their domain names.
 */
class SqlDelightSettingsLocalStoreTest {

    private fun newDb() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    @Test
    fun emptyStoreObservesNull() = runTest {
        SqlDelightSettingsLocalStore(newDb(), Dispatchers.Default).observeSettings().test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertRoundTripsThroughRealSqlite() = runTest {
        val store = SqlDelightSettingsLocalStore(newDb(), Dispatchers.Default)
        val settings = UserSettings(
            themeFamily = ThemeFamily.Mono,
            themeMode = ThemeMode.Dark,
            globalDoneVisibilitySeconds = 259200L,
            dashboardDoneVisibilitySeconds = 86400L,
            timeZone = "America/Los_Angeles",
            trackingEnabled = true,
            dragAndDropEnabled = true,
            username = "sampleuser",
        )

        store.upsert(settings)

        assertEquals(settings, store.currentSettings())
        store.observeSettings().test {
            assertEquals(settings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun secondUpsertReplacesTheSingletonRow() = runTest {
        val store = SqlDelightSettingsLocalStore(newDb(), Dispatchers.Default)
        store.upsert(UserSettings(themeFamily = ThemeFamily.Deferno, themeMode = ThemeMode.Light))

        store.upsert(UserSettings(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark))

        // Still exactly one row, now the latest values (the CHECK(id=0) singleton).
        assertEquals(ThemeFamily.Mono, store.currentSettings()?.themeFamily)
        assertEquals(ThemeMode.Dark, store.currentSettings()?.themeMode)
    }
}
