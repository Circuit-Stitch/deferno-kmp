package com.circuitstitch.deferno.feature.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DefaultSettingsComponent] (#72, ADR-0007 tier 3): the tier-3 drill-down — opening a category pushes
 * its detail child and [onBack] pops cleanly back to the list; the backed-category intents call the
 * writer (and the change is reflected in the observed [SettingsComponent.settings] StateFlow — the
 * live-apply round-trip); the coming-soon categories open a stub detail (no crash, no dead tap); and
 * the host-routed intents (App Permissions / Security 2FA / Account-Profile) emit the right [Output].
 * Driven on [Dispatchers.Unconfined] so the writer's optimistic apply is observable synchronously.
 */
class SettingsComponentTest {

    private fun component(
        initial: UserSettings = UserSettings.Default,
        output: (SettingsComponent.Output) -> Unit = {},
    ): Triple<DefaultSettingsComponent, FakeSettingsRepository, FakeSettingsWriter> {
        val repo = FakeSettingsRepository(initial)
        val writer = FakeSettingsWriter(repo)
        val component = DefaultSettingsComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            settingsRepository = repo,
            settingsWriter = writer,
            output = output,
            coroutineContext = Dispatchers.Unconfined,
        )
        return Triple(component, repo, writer)
    }

    @Test
    fun opensAtTheCategoryList() {
        val (component, _, _) = component()
        assertEquals(SettingsComponent.SettingsChild.List, component.stack.value.active.instance)
    }

    @Test
    fun openingACategoryPushesItsDetail_andBackPopsToTheList() {
        val (component, _, _) = component()

        component.openCategory(SettingsCategory.Appearance)
        val detail = assertIs<SettingsComponent.SettingsChild.Detail>(component.stack.value.active.instance)
        assertEquals(SettingsCategory.Appearance, detail.category)

        assertTrue(component.onBack(), "back pops the category detail")
        assertEquals(SettingsComponent.SettingsChild.List, component.stack.value.active.instance)

        assertFalse(component.onBack(), "back at the list root is not consumed")
    }

    @Test
    fun appearanceFamilyAndMode_callTheWriter_andReflectInSettings() {
        val (component, _, writer) = component(initial = UserSettings.Default)

        component.onThemeFamilyChanged(ThemeFamily.Mono)
        assertEquals(listOf(ThemeFamily.Mono to ThemeMode.Auto), writer.themeChanges)
        assertEquals(ThemeFamily.Mono, component.settings.value.themeFamily)

        component.onThemeModeChanged(ThemeMode.Dark)
        // The second change carries the already-applied family — independent fields don't clobber.
        assertEquals(ThemeFamily.Mono to ThemeMode.Dark, writer.themeChanges.last())
        assertEquals(ThemeMode.Dark, component.settings.value.themeMode)
    }

    @Test
    fun taskBehaviorAndDataPrivacyToggles_callTheWriter() {
        val (component, _, writer) = component()

        component.onDragAndDropChanged(true)
        component.onTrackingChanged(true)
        component.onDoneVisibilityChanged(259200L, 86400L)

        assertEquals(listOf(true), writer.dragAndDropChanges)
        assertEquals(listOf(true), writer.trackingChanges)
        assertEquals(listOf<Pair<Long?, Long?>>(259200L to 86400L), writer.doneVisibilityChanges)
        assertEquals(true, component.settings.value.dragAndDropEnabled)
        assertEquals(true, component.settings.value.trackingEnabled)
    }

    @Test
    fun comingSoonCategoriesOpenAStubDetail_noCrashNoDeadTap() {
        for (category in listOf(SettingsCategory.Security2FA, SettingsCategory.Integrations)) {
            val (component, _, _) = component()
            assertFalse(category.backed, "the unbacked category is a stub")

            component.openCategory(category)

            val detail = assertIs<SettingsComponent.SettingsChild.Detail>(component.stack.value.active.instance)
            assertEquals(category, detail.category)
            // It is a real detail child (not a dead tap) and backs out cleanly.
            assertTrue(component.onBack())
        }
    }

    @Test
    fun appPermissions_emitsOpenOsAppSettings() {
        val outputs = mutableListOf<SettingsComponent.Output>()
        val (component, _, _) = component(output = outputs::add)

        component.onOpenAppPermissions()

        assertEquals(listOf<SettingsComponent.Output>(SettingsComponent.Output.OpenOsAppSettings), outputs)
    }

    @Test
    fun security2FA_emitsOpenConsoleUrl_andAccount_emitsOpenProfile() {
        val outputs = mutableListOf<SettingsComponent.Output>()
        val (component, _, _) = component(output = outputs::add)

        component.onOpenConsole()
        component.onOpenProfile()

        assertEquals(
            listOf<SettingsComponent.Output>(
                SettingsComponent.Output.OpenConsoleUrl,
                SettingsComponent.Output.OpenProfile,
            ),
            outputs,
        )
    }

    @Test
    fun everyWireframeCategoryIsListed_backedAndUnbacked() {
        // The catalog must render ALL wireframe categories (#72): seven backed + two coming-soon stubs.
        assertEquals(7, SettingsCategory.entries.count { it.backed })
        assertEquals(
            listOf(SettingsCategory.Security2FA, SettingsCategory.Integrations),
            SettingsCategory.entries.filterNot { it.backed },
        )
    }
}
