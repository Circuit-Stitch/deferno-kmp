package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.network.dto.UserSettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `UserSettingsDto` ⇄ domain mapping (#72, ADR-0011 "condense at the edge"). Pins the tolerant
 * read (wire `"deferno"`/`"light"` tokens → enums; `null`/unknown → the default Deferno/Auto) and the
 * round-trip of the toggles + done-visibility windows, plus the inverse write tokens the outbox PATCH
 * bodies use. The captured `settings.json` golden fixture is asserted separately in
 * [com.circuitstitch.deferno.core.network.ContractFixtureParseTest].
 */
class SettingsMapperTest {

    @Test
    fun mapsWireTokensToEnums() {
        val dto = UserSettingsDto(
            globalDoneVisibilitySeconds = 259200L,
            dashboardDoneVisibilitySeconds = 86400L,
            themeFamily = "mono",
            themeMode = "dark",
            timeZone = "America/Los_Angeles",
            trackingEnabled = true,
            dragAndDropEnabled = true,
        )

        val settings = dto.toDomain()

        assertEquals(ThemeFamily.Mono, settings.themeFamily)
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertEquals(259200L, settings.globalDoneVisibilitySeconds)
        assertEquals(86400L, settings.dashboardDoneVisibilitySeconds)
        assertEquals("America/Los_Angeles", settings.timeZone)
        assertEquals(true, settings.trackingEnabled)
        assertEquals(true, settings.dragAndDropEnabled)
    }

    @Test
    fun degradesNullThemeTokensToDefault() {
        val settings = UserSettingsDto(themeFamily = null, themeMode = null).toDomain()

        assertEquals(ThemeFamily.Deferno, settings.themeFamily)
        assertEquals(ThemeMode.Auto, settings.themeMode)
    }

    @Test
    fun degradesUnknownThemeTokensToDefault() {
        val settings = UserSettingsDto(themeFamily = "neon", themeMode = "sepia").toDomain()

        assertEquals(ThemeFamily.Deferno, settings.themeFamily)
        assertEquals(ThemeMode.Auto, settings.themeMode)
    }

    @Test
    fun mapsTheLightAndDefernoTokens() {
        val settings = UserSettingsDto(themeFamily = "deferno", themeMode = "light").toDomain()

        assertEquals(ThemeFamily.Deferno, settings.themeFamily)
        assertEquals(ThemeMode.Light, settings.themeMode)
    }

    @Test
    fun writeTokensAreTheInverseOfTheRead() {
        assertEquals("deferno", ThemeFamily.Deferno.toWireToken())
        assertEquals("mono", ThemeFamily.Mono.toWireToken())
        assertEquals("light", ThemeMode.Light.toWireToken())
        assertEquals("dark", ThemeMode.Dark.toWireToken())
        assertEquals("auto", ThemeMode.Auto.toWireToken())

        // round-trip: token → enum → token
        for (family in ThemeFamily.entries) {
            assertEquals(family, family.toWireToken().toThemeFamily())
        }
        for (mode in ThemeMode.entries) {
            assertEquals(mode, mode.toWireToken().toThemeMode())
        }
    }
}
