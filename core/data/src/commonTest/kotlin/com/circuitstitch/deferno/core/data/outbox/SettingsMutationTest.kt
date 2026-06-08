package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The settings intent → endpoint → minimal-body table (#72, ADR-0001/0011). Pins the EXACT wire
 * request each [SettingsMutation] emits — every body lists only the keys it changes, a cleared
 * done-visibility window emits an explicit `null` (distinct from omit), and all PATCH the same
 * `auth/me/settings` path — and proves the optimistic [SettingsMutation.applyTo] transforms are
 * correct and idempotent (replay-safe). Mirrors [MutationTest] for the Task/Plan intents.
 */
class SettingsMutationTest {

    // --- the intent → endpoint → minimal-body table ---

    @Test
    fun setThemeEmitsFamilyAndModeTokens() {
        val request = SetTheme(ThemeFamily.Mono, ThemeMode.Dark).toRequest()
        assertEquals(OutboxMethod.Patch, request.method)
        assertEquals(listOf("auth", "me", "settings"), request.path)
        assertEquals("""{"theme_family":"mono","theme_mode":"dark"}""", request.body)
    }

    @Test
    fun setThemeUsesWireTokensNotDomainNames() {
        assertEquals(
            """{"theme_family":"deferno","theme_mode":"auto"}""",
            SetTheme(ThemeFamily.Deferno, ThemeMode.Auto).toRequest().body,
        )
        assertEquals(
            """{"theme_family":"deferno","theme_mode":"light"}""",
            SetTheme(ThemeFamily.Deferno, ThemeMode.Light).toRequest().body,
        )
    }

    @Test
    fun setTrackingEmitsOnlyTracking() {
        assertEquals("""{"tracking_enabled":true}""", SetTracking(true).toRequest().body)
        assertEquals("""{"tracking_enabled":false}""", SetTracking(false).toRequest().body)
    }

    @Test
    fun setDragAndDropEmitsOnlyDragAndDrop() {
        assertEquals("""{"drag_and_drop_enabled":true}""", SetDragAndDrop(true).toRequest().body)
        assertEquals("""{"drag_and_drop_enabled":false}""", SetDragAndDrop(false).toRequest().body)
    }

    @Test
    fun setDoneVisibilityEmitsBothWindows() {
        val request = SetDoneVisibility(259200L, 86400L).toRequest()
        assertEquals(OutboxMethod.Patch, request.method)
        assertEquals(
            """{"global_done_visibility_seconds":259200,"dashboard_done_visibility_seconds":86400}""",
            request.body,
        )
    }

    @Test
    fun setDoneVisibilityEmitsExplicitNullToClearAWindow() {
        // null = "clear it" (ADR-0011), NOT an omitted field.
        assertEquals(
            """{"global_done_visibility_seconds":null,"dashboard_done_visibility_seconds":null}""",
            SetDoneVisibility(null, null).toRequest().body,
        )
    }

    @Test
    fun allSettingsIntentsShareTheSameTarget() {
        assertEquals("settings", SetTheme(ThemeFamily.Deferno, ThemeMode.Auto).target)
        assertEquals("settings", SetTracking(true).target)
        assertEquals("settings", SetDragAndDrop(false).target)
        assertEquals("settings", SetDoneVisibility(1L, 2L).target)
    }

    // --- optimistic apply: correctness + idempotence (replay-safety) ---

    @Test
    fun applyTransformsTheRightField() {
        val base = UserSettings.Default
        assertEquals(ThemeFamily.Mono, SetTheme(ThemeFamily.Mono, ThemeMode.Dark).applyTo(base).themeFamily)
        assertEquals(ThemeMode.Dark, SetTheme(ThemeFamily.Mono, ThemeMode.Dark).applyTo(base).themeMode)
        assertEquals(true, SetTracking(true).applyTo(base).trackingEnabled)
        assertEquals(true, SetDragAndDrop(true).applyTo(base).dragAndDropEnabled)
        assertEquals(99L, SetDoneVisibility(99L, 7L).applyTo(base).globalDoneVisibilitySeconds)
        assertEquals(7L, SetDoneVisibility(99L, 7L).applyTo(base).dashboardDoneVisibilitySeconds)
    }

    @Test
    fun applyIsIdempotent() {
        val base = UserSettings.Default
        val intents = listOf(
            SetTheme(ThemeFamily.Mono, ThemeMode.Light),
            SetTracking(true),
            SetDragAndDrop(true),
            SetDoneVisibility(10L, 20L),
            SetDoneVisibility(null, null),
        )
        for (intent in intents) {
            val once = intent.applyTo(base)
            assertEquals(once, intent.applyTo(once), "applyTo must be idempotent for $intent")
        }
    }
}
