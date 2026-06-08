package com.circuitstitch.deferno.feature.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The Settings Destination's category catalog (#72, ADR-0007 tier 3 / ADR-0015). Every wireframe
 * category is listed — the **backed** ones are functional over `UserSettings` get/patch, the two
 * **unbacked** ones ([Security2FA], [Integrations]) render gentle coming-soon stubs (a deliberate,
 * scoped exception to "no dead ends": a settings list missing obvious rows reads as broken). The
 * View renders one row per entry; tapping one drills into [SettingsChild] for it.
 */
enum class SettingsCategory(val backed: Boolean) {
    Appearance(backed = true),
    TaskBehavior(backed = true),
    DataPrivacy(backed = true),
    HelpFeedback(backed = true),
    AppPermissions(backed = true),
    Legal(backed = true),
    Account(backed = true),
    Security2FA(backed = false),
    Integrations(backed = false),
}

/**
 * The **Settings** Destination component (#72, ADR-0013 / ADR-0007 tier 3): a **drill-down** modelled
 * as a Decompose [ChildStack] — the category [SettingsChild.List] at the root, and a per-category
 * detail pushed above it ([openCategory]) and popped cleanly back ([onBack]). It is Compose-free and
 * iOS-capable (ADR-0004): the View renders [stack].
 *
 * The backed categories observe the Active Account's [UserSettings] as one shared [settings] StateFlow
 * (offline-first, ADR-0001 — seeded with [UserSettings.Default] so the detail screens always have a
 * value) and write through [SettingsWriter] (optimistic local apply + outbox enqueue — Appearance
 * applies **live** because the same `Flow` drives the app-wide theme). Host-level concerns the slice
 * can't own — opening OS app settings, the Zitadel console URL, or the Profile Destination — are
 * emitted as [Output] for the shell to route (the same Output-up routing Plan/Tasks/Profile use).
 */
interface SettingsComponent {
    /** The tier-3 drill-down: the category list at the root, a category detail pushed above it. */
    val stack: Value<ChildStack<*, SettingsChild>>

    /** The Active Account's settings — drives every backed category and the app-wide live theme. */
    val settings: StateFlow<UserSettings>

    /** Drill into [category]'s detail (push). */
    fun openCategory(category: SettingsCategory)

    /** Back out of an open category detail to the list (pop); `false` when already at the list root. */
    fun onBack(): Boolean

    // --- backed-category intents (optimistic apply + persist via SettingsWriter) ---

    /** Appearance: set the theme family — applies live + persists (#72). */
    fun onThemeFamilyChanged(family: ThemeFamily)

    /** Appearance: set the theme mode (light/dark/auto) — applies live + persists (#72). */
    fun onThemeModeChanged(mode: ThemeMode)

    /** Task behavior: toggle the experimental drag-and-drop affordance. */
    fun onDragAndDropChanged(enabled: Boolean)

    /** Task behavior: set the done-visibility windows in seconds (`null` clears a window). */
    fun onDoneVisibilityChanged(globalSeconds: Long?, dashboardSeconds: Long?)

    /** Data & Privacy: toggle analytics/tracking. */
    fun onTrackingChanged(enabled: Boolean)

    // --- host-routed intents (Output up to the shell) ---

    /**
     * Data & Privacy: ask the host to open the web app's export/import surface. There is no client
     * REST endpoint at envelope v0.1 (export/import is deferred), so it is **reachable** as a web
     * action rather than handled in-app — not dead prose (AC #3, ADR-0015).
     */
    fun onOpenDataExportImport()

    /**
     * Help & Feedback: ask the host to open the web app's submit-feedback surface. Same deferral as
     * export/import — no in-app feedback endpoint at v0.1, but the tap is **reachable** (AC #4).
     */
    fun onOpenSubmitFeedback()

    /** App Permissions: ask the host to deep-link to the OS app-settings screen. */
    fun onOpenAppPermissions()

    /** Security & 2FA (stub): ask the host to open the Zitadel console URL when present. */
    fun onOpenConsole()

    /** Account: ask the host to open the Profile Destination for identity. */
    fun onOpenProfile()

    /** A live category detail, tagged with which [SettingsCategory] it is (for the unbacked stubs). */
    sealed interface SettingsChild {
        /** The category list root. */
        data object List : SettingsChild

        /** A category detail; [category] selects which screen the View renders. */
        data class Detail(val category: SettingsCategory) : SettingsChild
    }

    sealed interface Output {
        /** Open the OS app-settings screen for this app (App Permissions category). */
        data object OpenOsAppSettings : Output

        /** Open the web app's data export/import surface (Data & Privacy — no client endpoint at v0.1). */
        data object OpenDataExportImport : Output

        /** Open the web app's submit-feedback surface (Help & Feedback — no client endpoint at v0.1). */
        data object OpenSubmitFeedback : Output

        /** Open the Zitadel admin console (Security & 2FA stub) — handled only when a URL is present. */
        data object OpenConsoleUrl : Output

        /** Switch to the Profile Destination (Account category links to identity). */
        data object OpenProfile : Output
    }
}

class DefaultSettingsComponent(
    componentContext: ComponentContext,
    private val settingsRepository: SettingsRepository,
    private val settingsWriter: SettingsWriter,
    private val output: (SettingsComponent.Output) -> Unit = {},
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : SettingsComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())
        .also { s -> lifecycle.doOnDestroy { s.cancel() } }

    // Plain-data configs (serializer = null → no state restoration wired in v1, matching the feature
    // components). The root is List; each category detail is pushed as Category(category).
    private sealed interface Config {
        data object List : Config
        data class Category(val category: SettingsCategory) : Config
    }

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, SettingsComponent.SettingsChild>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = Config.List,
            key = "SettingsStack",
            handleBackButton = false, // tier-3 back is routed via onBack(), not a global stack pop
            childFactory = ::createChild,
        )

    override val settings: StateFlow<UserSettings> =
        settingsRepository.observeSettings()
            .stateIn(scope, SharingStarted.Eagerly, UserSettings.Default)

    // `push` is a DelicateDecomposeApi (a direct imperative push rather than a navigate-transform);
    // intentional here — a tier-3 drill-down is exactly a push/pop stack (the same shape the docs note
    // push is fine for), and the configs are plain data so there is no transform to reuse.
    @OptIn(DelicateDecomposeApi::class)
    override fun openCategory(category: SettingsCategory) {
        navigation.push(Config.Category(category))
    }

    override fun onBack(): Boolean =
        if (stack.value.active.configuration is Config.Category) {
            navigation.pop()
            true
        } else {
            false
        }

    override fun onThemeFamilyChanged(family: ThemeFamily) {
        scope.launch { settingsWriter.setTheme(family, settings.value.themeMode) }
    }

    override fun onThemeModeChanged(mode: ThemeMode) {
        scope.launch { settingsWriter.setTheme(settings.value.themeFamily, mode) }
    }

    override fun onDragAndDropChanged(enabled: Boolean) {
        scope.launch { settingsWriter.setDragAndDrop(enabled) }
    }

    override fun onDoneVisibilityChanged(globalSeconds: Long?, dashboardSeconds: Long?) {
        scope.launch { settingsWriter.setDoneVisibility(globalSeconds, dashboardSeconds) }
    }

    override fun onTrackingChanged(enabled: Boolean) {
        scope.launch { settingsWriter.setTracking(enabled) }
    }

    override fun onOpenDataExportImport() = output(SettingsComponent.Output.OpenDataExportImport)

    override fun onOpenSubmitFeedback() = output(SettingsComponent.Output.OpenSubmitFeedback)

    override fun onOpenAppPermissions() = output(SettingsComponent.Output.OpenOsAppSettings)

    override fun onOpenConsole() = output(SettingsComponent.Output.OpenConsoleUrl)

    override fun onOpenProfile() = output(SettingsComponent.Output.OpenProfile)

    private fun createChild(
        config: Config,
        @Suppress("UNUSED_PARAMETER") childContext: ComponentContext,
    ): SettingsComponent.SettingsChild =
        when (config) {
            Config.List -> SettingsComponent.SettingsChild.List
            is Config.Category -> SettingsComponent.SettingsChild.Detail(config.category)
        }
}
