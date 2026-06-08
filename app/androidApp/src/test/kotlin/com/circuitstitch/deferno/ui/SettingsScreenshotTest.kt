package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roborazzi screenshot baselines (#72) for the Settings tier-3 drill-down: the category list (with
 * the coming-soon stub rows), the Appearance detail (deferno/light and mono/dark), and a coming-soon
 * stub detail. Drives a real [DefaultSettingsComponent] over the in-memory settings fakes (the same
 * harness the interaction tests use). Record with `:app:androidApp:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun component(category: SettingsCategory? = null) = DefaultSettingsComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        settingsRepository = FakeSettingsRepository(),
        settingsWriter = FakeSettingsWriter(),
        coroutineContext = Dispatchers.Unconfined,
    ).also { if (category != null) it.openCategory(category) }

    private fun capture(
        name: String,
        palette: DefernoPalette = DefernoPalette.Deferno,
        darkTheme: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            DefernoTheme(palette = palette, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun categoryList_light() = capture("settings_list_light") { SettingsScreen(component()) }

    @Test
    fun appearanceDetail_defernoLight() =
        capture("settings_appearance_deferno_light") { SettingsScreen(component(SettingsCategory.Appearance)) }

    @Test
    fun appearanceDetail_monoDark() =
        capture("settings_appearance_mono_dark", palette = DefernoPalette.Mono, darkTheme = true) {
            SettingsScreen(component(SettingsCategory.Appearance))
        }

    @Test
    fun comingSoonStub_light() =
        capture("settings_coming_soon_light") { SettingsScreen(component(SettingsCategory.Integrations)) }
}
