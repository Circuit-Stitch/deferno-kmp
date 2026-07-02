package com.circuitstitch.deferno.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.circuitstitch.deferno.core.agent.InferenceEngineAvailability
import com.circuitstitch.deferno.core.agent.InferenceEngineId
import com.circuitstitch.deferno.core.agent.InferenceEngineOption
import com.circuitstitch.deferno.core.agent.InferenceEngineOrigin
import com.circuitstitch.deferno.core.data.attachment.StorageProviderAvailability
import com.circuitstitch.deferno.core.data.attachment.StorageProviderId
import com.circuitstitch.deferno.core.data.attachment.StorageProviderOption
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.auth_add_another_account
import com.circuitstitch.deferno.core.designsystem.resources.common_account
import com.circuitstitch.deferno.core.designsystem.resources.common_open_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.feedback_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_engine_deferno_cloud
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_engine_off
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_engine_on_device_basics
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_intro
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_note_cloud
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_note_on_device
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_note_premium
import com.circuitstitch.deferno.core.designsystem.resources.settings_agent_off_note
import com.circuitstitch.deferno.core.designsystem.resources.settings_appearance_mode_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_appearance_theme_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_agent
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_app_permissions
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_appearance
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_data_privacy
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_help_feedback
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_integrations
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_legal
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_security_2fa
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_speech_engine
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_storage
import com.circuitstitch.deferno.core.designsystem.resources.settings_category_task_behavior
import com.circuitstitch.deferno.core.designsystem.resources.settings_coming_soon_integrations_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_coming_soon_security_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_coming_soon_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_export_web_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_export_web_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_your_data_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_always
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_dashboard
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_everywhere
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_one_day
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_one_week
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_done_visibility_three_days
import com.circuitstitch.deferno.core.designsystem.resources.settings_help_feedback_intro
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_open_source_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_open_source_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_privacy_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_privacy_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_terms_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_terms_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_privacy_analytics_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_privacy_analytics_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_row_summary_unavailable
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_open_console_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_engine_automatic
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_engine_system_dictation
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_engine_whisper
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_intro
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_note_automatic
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_note_downloading
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_note_no_engine
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_note_preparing
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_note_unsupported_locale
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_intro
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_keep_recordings_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_keep_recordings_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_note_coming_later
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_note_deferno_backend
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_note_on_device
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_provider_deferno_backend
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_provider_dropbox
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_provider_google_drive
import com.circuitstitch.deferno.core.designsystem.resources.settings_storage_provider_on_device
import com.circuitstitch.deferno.core.designsystem.resources.settings_task_behavior_drag_drop_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_task_behavior_drag_drop_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_family_deferno
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_family_mono
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_mode_dark
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_mode_follow_system
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_mode_light
import com.circuitstitch.deferno.core.designsystem.resources.shell_destination_assistant
import com.circuitstitch.deferno.core.designsystem.resources.shell_signed_in
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.core.speech.UnavailableReason
import com.circuitstitch.deferno.feature.settings.InferenceEngineSettings
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SpeechEngineSettings
import com.circuitstitch.deferno.feature.settings.StorageProviderSettings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Minimum height for a clickable row/control — design-principles.md "≥44–48dp" targets. */
private val MinTouchTarget = 48.dp

/**
 * The **Settings** Destination View, desktop edition (#85, ADR-0013 / ADR-0007 tier 3 / ADR-0017) — the
 * desktop counterpart of the Android `SettingsScreen`. A thin renderer of the shared, Compose-free
 * [SettingsComponent]'s tier-3 drill-down (ADR-0003: holds no logic): it renders the category **list**
 * at the root and a **per-category detail** when one is open, switching on the component's
 * [SettingsComponent.stack], and forwards every interaction as an intent.
 *
 * **Appearance is live**: choosing a [ThemeFamily] / [ThemeMode] calls the component intent, which the
 * shared component persists optimistically; because the same `UserSettings` flow drives the desktop
 * root's theme (`themeMode.resolveDark(systemDark)` + `themeFamily`, ADR-0017), the window re-themes
 * immediately. The other backed categories render functional over the live [UserSettings]; the two
 * unbacked categories (Security & 2FA, Integrations) render gentle coming-soon stubs (no dead taps,
 * ADR-0015).
 *
 * **Desktop divergence from Android**: the **App Permissions** category is omitted — there is no per-app
 * OS settings screen on desktop (ADR-0017), so its row is dropped and the component's `OpenOsAppSettings`
 * Output is left unhandled on this host. Host-routed Outputs (Data & Privacy export/import, Help &
 * Feedback submit, Security & 2FA console URL, Account → Profile) are wired by the desktop shell host.
 */
@Composable
fun SettingsDesktopScreen(component: SettingsComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    val settings by component.settings.collectAsState()
    val speechEngine by component.speechEngine.collectAsState()
    val inferenceEngine by component.inferenceEngine.collectAsState()
    val storageProvider by component.storageProvider.collectAsState()

    when (val child = stack.active.instance) {
        SettingsComponent.SettingsChild.List ->
            SettingsListContent(
                onOpenCategory = component::openCategory,
                speechEngine = speechEngine,
                inferenceEngine = inferenceEngine,
                storageProvider = storageProvider,
                modifier = modifier,
            )

        is SettingsComponent.SettingsChild.Detail ->
            CategoryDetail(
                category = child.category,
                settings = settings,
                speechEngine = speechEngine,
                inferenceEngine = inferenceEngine,
                storageProvider = storageProvider,
                component = component,
                modifier = modifier,
            )
    }
}

/**
 * The desktop category list omits App Permissions (no per-app OS settings screen, ADR-0017) and the
 * Assistant row (#282/ADR-0040: the chat surface is iOS-only in v1). The **Agent** (cloud inference
 * selector — needs no on-device ML) and **Storage** (on-device storage already works on desktop) rows
 * are shown, at parity with Android; the Agent row self-hides via the `available` guard below when the
 * inference catalog is empty.
 */
private val DesktopCategories: List<SettingsCategory> =
    SettingsCategory.entries.filter {
        it != SettingsCategory.AppPermissions &&
            // The Assistant (#282, ADR-0040) is iOS-only in v1; the desktop chat View is deferred.
            it != SettingsCategory.Assistant
    }

// --- category list (root) ---

@Composable
internal fun SettingsListContent(
    onOpenCategory: (SettingsCategory) -> Unit,
    speechEngine: SpeechEngineSettings,
    inferenceEngine: InferenceEngineSettings,
    storageProvider: StorageProviderSettings,
    modifier: Modifier = Modifier,
) {
    // The "Settings" title now lives in the shell's single top bar (Cand 1); this pane is just the list.
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        DesktopCategories.forEach { category ->
            // The device-local Speech engine row shows only when this device has a real engine (#93) —
            // hidden on desktop until a desktop engine lands (#94); shown automatically once it does.
            if (category == SettingsCategory.SpeechEngine && !speechEngine.available) return@forEach
            // The Agent row shows only when the inference catalog has options (the cloud engine registers
            // regardless of platform, so it shows on desktop too) — parity with Android (#150).
            if (category == SettingsCategory.Agent && !inferenceEngine.available) return@forEach
            CategoryRow(
                label = category.title,
                summary = category.rowSummary(speechEngine, inferenceEngine, storageProvider),
                onClick = { onOpenCategory(category) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CategoryRow(label: String, summary: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(Res.string.common_open_named_cd, label), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.defernoColors.inkMuted,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

// --- per-category detail ---

@Composable
private fun CategoryDetail(
    category: SettingsCategory,
    settings: UserSettings,
    speechEngine: SpeechEngineSettings,
    inferenceEngine: InferenceEngineSettings,
    storageProvider: StorageProviderSettings,
    component: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    // The category title + Back now live in the shell's single top bar (Cand 1); this pane is the body.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (category) {
            SettingsCategory.Appearance -> AppearanceDetail(settings, component)
            SettingsCategory.TaskBehavior -> TaskBehaviorDetail(settings, component)
            SettingsCategory.SpeechEngine -> SpeechEngineDetail(speechEngine, component::onSpeechEngineSelected)
            SettingsCategory.DataPrivacy -> DataPrivacyDetail(settings, component)
            SettingsCategory.HelpFeedback -> HelpFeedbackDetail(component)
            SettingsCategory.Legal -> LegalDetail()
            SettingsCategory.Account -> AccountDetail(component)
            SettingsCategory.Security2FA -> ComingSoonDetail(
                body = stringResource(Res.string.settings_coming_soon_security_body),
                action = stringResource(Res.string.settings_security_open_console_button),
                onAction = component::onOpenConsole,
            )

            SettingsCategory.Integrations -> ComingSoonDetail(
                body = stringResource(Res.string.settings_coming_soon_integrations_body),
            )

            // The Agent inference-engine selector (#150): the cloud engine needs no on-device ML, so the
            // row is live on desktop. Picking the cloud engine is the explicit opt-in; an unentitled cloud
            // engine renders disabled ("Premium"), never selectable.
            SettingsCategory.Agent -> AgentDetail(inferenceEngine, component::onInferenceEngineSelected)

            // The Storage provider selector (#210) — on-device storage already works on desktop. The
            // brain-dump-retention toggle lives here too (recordings are on-device attachments).
            SettingsCategory.Storage -> {
                val keepRecordings by component.keepBrainDumpRecordings.collectAsState()
                StorageProviderDetail(
                    storageProvider,
                    component::onStorageProviderSelected,
                    keepRecordings,
                    component::onKeepBrainDumpRecordingsChanged,
                )
            }

            // App Permissions is omitted from the desktop list (no per-app OS settings screen,
            // ADR-0017), so this branch is unreachable on desktop — kept only to stay exhaustive.
            SettingsCategory.AppPermissions -> Unit

            // The Assistant (#282) is iOS-only in v1; filtered from DesktopCategories, never opened here.
            SettingsCategory.Assistant -> Unit
        }
    }
}

@Composable
private fun AppearanceDetail(settings: UserSettings, component: SettingsComponent) {
    SectionLabel(stringResource(Res.string.settings_appearance_theme_section))
    ThemeFamily.entries.forEach { family ->
        ChoiceRow(
            label = family.label,
            selected = settings.themeFamily == family,
            onSelect = { component.onThemeFamilyChanged(family) },
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel(stringResource(Res.string.settings_appearance_mode_section))
    ThemeMode.entries.forEach { mode ->
        ChoiceRow(
            label = mode.label,
            selected = settings.themeMode == mode,
            onSelect = { component.onThemeModeChanged(mode) },
        )
    }
}

@Composable
private fun SpeechEngineDetail(state: SpeechEngineSettings, onSelect: (SpeechEngineId) -> Unit) {
    Text(
        // The App-setting nature, stated plainly (AC #2): device-local, never synced, never per-Account.
        text = stringResource(Res.string.settings_speech_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    state.options.forEach { option ->
        EngineChoiceRow(
            label = speechEngineLabel(option.id),
            note = speechEngineNote(option),
            selected = state.selected == option.id,
            onSelect = { onSelect(option.id) },
        )
    }
}

@Composable
private fun AgentDetail(state: InferenceEngineSettings, onSelect: (InferenceEngineId) -> Unit) {
    // The App-setting nature + the AI consent, stated plainly (AC1): device-local, never synced; an
    // on-device engine keeps your text local, the cloud engine sends it off-device, and "Off" runs nothing.
    // Picking the cloud engine is the explicit opt-in — it still needs your account to be entitled (AC2).
    Text(
        text = stringResource(Res.string.settings_agent_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    // "Off" is always offered first (the default); then each engine registered on this device. A cloud
    // engine the Account isn't entitled to is shown **disabled** ("Premium"), never selectable (AC2).
    EngineChoiceRow(
        label = stringResource(Res.string.settings_agent_engine_off),
        note = stringResource(Res.string.settings_agent_off_note),
        selected = state.selected == InferenceEngineId.Off,
        onSelect = { onSelect(InferenceEngineId.Off) },
    )
    state.options.forEach { option ->
        val locked = option.availability is InferenceEngineAvailability.RequiresPremium
        EngineChoiceRow(
            label = inferenceEngineLabel(option.id),
            note = inferenceEngineNote(option),
            selected = state.selected == option.id,
            onSelect = { onSelect(option.id) },
            enabled = !locked,
        )
    }
}

@Composable
private fun StorageProviderDetail(
    state: StorageProviderSettings,
    onSelect: (StorageProviderId) -> Unit,
    keepRecordings: Boolean,
    onKeepRecordingsChange: (Boolean) -> Unit,
) {
    // The App-setting nature, stated plainly (#210): device-local, never synced. On-device keeps attachment
    // bytes on this device; the user-owned cloud providers are coming later. Feedback attachments are
    // separate — they always go to Deferno so the team can see what you send.
    Text(
        text = stringResource(Res.string.settings_storage_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    state.options.forEach { option ->
        val locked = option.availability is StorageProviderAvailability.ComingLater
        EngineChoiceRow(
            label = storageProviderLabel(option.id),
            note = storageProviderNote(option),
            selected = state.selected == option.id,
            onSelect = { onSelect(option.id) },
            enabled = !locked,
        )
    }
    // Brain-dump recording retention (#211): keep the source voice recording as an on-device attachment when
    // a draft is accepted, so the person can revisit what they said. Device-local, default on; recognition
    // still never leaves the device.
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ToggleRow(
        label = stringResource(Res.string.settings_storage_keep_recordings_label),
        description = stringResource(Res.string.settings_storage_keep_recordings_description),
        checked = keepRecordings,
        onCheckedChange = onKeepRecordingsChange,
    )
}

@Composable
private fun TaskBehaviorDetail(settings: UserSettings, component: SettingsComponent) {
    ToggleRow(
        label = stringResource(Res.string.settings_task_behavior_drag_drop_label),
        description = stringResource(Res.string.settings_task_behavior_drag_drop_description),
        checked = settings.dragAndDropEnabled,
        onCheckedChange = component::onDragAndDropChanged,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel(stringResource(Res.string.settings_done_visibility_section))
    Text(
        text = stringResource(Res.string.settings_done_visibility_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    DoneVisibilityRow(
        label = stringResource(Res.string.settings_done_visibility_everywhere),
        seconds = settings.globalDoneVisibilitySeconds,
        onSelect = { component.onDoneVisibilityChanged(it, settings.dashboardDoneVisibilitySeconds) },
    )
    DoneVisibilityRow(
        label = stringResource(Res.string.settings_done_visibility_dashboard),
        seconds = settings.dashboardDoneVisibilitySeconds,
        onSelect = { component.onDoneVisibilityChanged(settings.globalDoneVisibilitySeconds, it) },
    )
}

@Composable
private fun DataPrivacyDetail(settings: UserSettings, component: SettingsComponent) {
    ToggleRow(
        label = stringResource(Res.string.settings_privacy_analytics_label),
        description = stringResource(Res.string.settings_privacy_analytics_description),
        checked = settings.trackingEnabled,
        onCheckedChange = component::onTrackingChanged,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel(stringResource(Res.string.settings_data_your_data_section))
    Text(
        text = stringResource(Res.string.settings_data_export_web_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    // Reachable web action, not dead prose (AC #3): the host deep-links the web app's data surface.
    TextButton(
        onClick = component::onOpenDataExportImport,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.settings_data_export_web_button)) }
}

@Composable
private fun HelpFeedbackDetail(component: SettingsComponent) {
    Text(
        text = stringResource(Res.string.settings_help_feedback_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    // Opens the in-app Feedback form overlay (#375) — the shell handles the submit, no web round-trip.
    TextButton(
        onClick = component::onOpenSubmitFeedback,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.feedback_title)) }
}

@Composable
private fun LegalDetail() {
    SectionLabel(stringResource(Res.string.settings_legal_terms_section))
    Text(
        text = stringResource(Res.string.settings_legal_terms_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    SectionLabel(stringResource(Res.string.settings_legal_privacy_section))
    Text(
        text = stringResource(Res.string.settings_legal_privacy_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    SectionLabel(stringResource(Res.string.settings_legal_open_source_section))
    Text(
        text = stringResource(Res.string.settings_legal_open_source_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
}

/**
 * The Account category is an **account switcher** (#NN): the signed-in roster — tap the active account
 * (chevron) to open its Profile (where identity + sign-out live), tap another to switch to it — plus
 * "Add another account" (re-enters sign-in, keeping the others). Time zone moved to Profile too. Mirrors
 * the Android screen.
 */
@Composable
private fun AccountDetail(component: SettingsComponent) {
    val accounts by component.accounts.collectAsState()
    val activeId = component.activeAccountId

    SectionLabel(stringResource(Res.string.shell_signed_in))
    accounts.forEach { account ->
        val isActive = account.id == activeId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                // The active account drills into its Profile; the others switch to themselves.
                .clickable { if (isActive) component.onOpenProfile() else component.onSwitchAccount(account.id) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isActive, onClick = null)
            Text(
                text = account.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            // The chevron marks the active row as a drill-in to its Profile.
            if (isActive) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }

    TextButton(
        onClick = component::onAddAccount,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.auth_add_another_account)) }
}

@Composable
private fun ComingSoonDetail(body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Text(
        text = stringResource(Res.string.settings_coming_soon_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    if (action != null && onAction != null) {
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(action)
        }
    }
}

// --- shared atoms (mirrors the Android SettingsScreen atoms; this module's androidMain copies are
// private, and jvmMain is a separate Compose-Desktop source set, so they are re-declared here) ---


@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A radio row that, unlike [ChoiceRow], carries an optional [note] subtitle (the engine availability).
 *  [enabled] = false (e.g. an unentitled cloud engine, or a coming-later provider) makes the row inert. */
@Composable
private fun EngineChoiceRow(
    label: String,
    note: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.defernoColors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        // The whole row is the toggle target (≥48dp), so tapping the label flips it too — and the
        // Switch defers its click to the row (onCheckedChange = null) so there is one click handler.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DoneVisibilityRow(label: String, seconds: Long?, onSelect: (Long?) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DoneVisibilityWindow.entries.forEach { window ->
                ChoiceChip(
                    label = stringResource(window.label),
                    selected = seconds == window.seconds,
                    onSelect = { onSelect(window.seconds) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    TextButton(
        onClick = onSelect,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) {
        if (selected) {
            Text("✓ $label", style = MaterialTheme.typography.labelLarge)
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.defernoColors.inkMuted)
        }
    }
}

// --- View-only labels (kept out of the shared domain/registry, like the nav-suite labels) ---

private val SettingsCategory.title: String
    @Composable get() = when (this) {
        SettingsCategory.Appearance -> stringResource(Res.string.settings_category_appearance)
        SettingsCategory.TaskBehavior -> stringResource(Res.string.settings_category_task_behavior)
        SettingsCategory.SpeechEngine -> stringResource(Res.string.settings_category_speech_engine)
        SettingsCategory.Agent -> stringResource(Res.string.settings_category_agent)
        SettingsCategory.Assistant -> stringResource(Res.string.shell_destination_assistant)
        SettingsCategory.Storage -> stringResource(Res.string.settings_category_storage)
        SettingsCategory.DataPrivacy -> stringResource(Res.string.settings_category_data_privacy)
        SettingsCategory.HelpFeedback -> stringResource(Res.string.settings_category_help_feedback)
        SettingsCategory.AppPermissions -> stringResource(Res.string.settings_category_app_permissions)
        SettingsCategory.Legal -> stringResource(Res.string.settings_category_legal)
        SettingsCategory.Account -> stringResource(Res.string.common_account)
        SettingsCategory.Security2FA -> stringResource(Res.string.settings_category_security_2fa)
        SettingsCategory.Integrations -> stringResource(Res.string.settings_category_integrations)
    }

/**
 * The category-row summary line: the Speech engine row shows the current choice (and flags it when the
 * chosen engine isn't usable yet — AC #3); the unbacked categories show "Coming soon"; the rest show none.
 */
@Composable
private fun SettingsCategory.rowSummary(
    speechEngine: SpeechEngineSettings,
    inferenceEngine: InferenceEngineSettings,
    storageProvider: StorageProviderSettings,
): String? = when {
    this == SettingsCategory.SpeechEngine -> {
        val selectedOption = speechEngine.options.firstOrNull { it.id == speechEngine.selected }
        val label = speechEngineLabel(speechEngine.selected)
        // Flag unavailability when the chosen engine reports Unavailable OR is no longer registered at all
        // (selected ∉ options) — both mean it can't transcribe now, so the row must reflect that (AC3).
        val unavailable = selectedOption == null || selectedOption.availability is SpeechAvailability.Unavailable
        if (unavailable) stringResource(Res.string.settings_row_summary_unavailable, label) else label
    }
    // The Agent row reflects the chosen engine ("Off" when off); the Storage row names the provider (#150/#210).
    this == SettingsCategory.Agent -> when (inferenceEngine.selected) {
        InferenceEngineId.Off -> stringResource(Res.string.settings_agent_engine_off)
        else -> inferenceEngineLabel(inferenceEngine.selected)
    }
    this == SettingsCategory.Storage -> storageProviderLabel(storageProvider.selected)
    !backed -> stringResource(Res.string.settings_coming_soon_title)
    else -> null
}

/** The human label for an engine id (View concern, like the nav-suite labels) — `Automatic` leads the row. */
@Composable
private fun speechEngineLabel(id: SpeechEngineId): String = when (id) {
    SpeechEngineId.Automatic -> stringResource(Res.string.settings_speech_engine_automatic)
    SpeechEngineId.Whisper -> stringResource(Res.string.settings_speech_engine_whisper)
    // The Sidecar-hosted native recognizer (#119, ADR-0024) — the OS's own dictation engine
    // (SFSpeechRecognizer on macOS), named for what it is to the user, not its plumbing.
    SpeechEngineId.Sidecar -> stringResource(Res.string.settings_speech_engine_system_dictation)
    // Future native fast paths get explicit labels as they land (#96/#97); fall back to a humanised id.
    else -> id.value.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** The per-engine subtitle: Automatic explains itself; a real engine shows *why* it isn't usable yet (AC #3). */
@Composable
private fun speechEngineNote(option: SpeechEngineOption): String? = when (option.id) {
    SpeechEngineId.Automatic -> stringResource(Res.string.settings_speech_note_automatic)
    else -> when (val availability = option.availability) {
        SpeechAvailability.Available -> null
        is SpeechAvailability.Unavailable -> when (availability.reason) {
            UnavailableReason.ModelMissing -> stringResource(Res.string.settings_speech_note_downloading)
            UnavailableReason.UnsupportedLocale -> stringResource(Res.string.settings_speech_note_unsupported_locale)
            UnavailableReason.NoEngine -> stringResource(Res.string.settings_speech_note_no_engine)
            UnavailableReason.NotReady -> stringResource(Res.string.settings_speech_note_preparing)
            // An absent optional fast path (no Sidecar Helper on this machine, ADR-0024) — permanent,
            // so it must never read as the transient "Preparing…".
            UnavailableReason.NotInstalled -> stringResource(Res.string.settings_speech_note_no_engine)
        }
    }
}

/** The human label for an inference-engine id (View concern, like the speech-engine labels). */
@Composable
private fun inferenceEngineLabel(id: InferenceEngineId): String = when (id) {
    InferenceEngineId.Off -> stringResource(Res.string.settings_agent_engine_off)
    InferenceEngineId.DefernoCloud -> stringResource(Res.string.settings_agent_engine_deferno_cloud)
    // The zero-ML deterministic floor — distinct from the planned on-device-LLM hybrid (ADR-0027).
    InferenceEngineId.OnDeviceFloor -> stringResource(Res.string.settings_agent_engine_on_device_basics)
    // Further on-device runtimes + a future BYO engine get explicit labels as they land; fall back to a humanised id.
    else -> id.value.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** The per-engine subtitle: where it runs, or *why* it isn't selectable yet (the premium upsell, AC2). */
@Composable
private fun inferenceEngineNote(option: InferenceEngineOption): String? = when (option.availability) {
    InferenceEngineAvailability.RequiresPremium -> stringResource(Res.string.settings_agent_note_premium)
    InferenceEngineAvailability.Available -> when (option.origin) {
        InferenceEngineOrigin.OnDevice -> stringResource(Res.string.settings_agent_note_on_device)
        InferenceEngineOrigin.DefernoCloud -> stringResource(Res.string.settings_agent_note_cloud)
    }
}

/** The human label for a storage-provider id (View concern, like the engine labels). */
@Composable
private fun storageProviderLabel(id: StorageProviderId): String = when (id) {
    StorageProviderId.OnDevice -> stringResource(Res.string.settings_storage_provider_on_device)
    StorageProviderId.DefernoBackend -> stringResource(Res.string.settings_storage_provider_deferno_backend)
    StorageProviderId.Dropbox -> stringResource(Res.string.settings_storage_provider_dropbox)
    StorageProviderId.GoogleDrive -> stringResource(Res.string.settings_storage_provider_google_drive)
    // Future user-owned providers get explicit labels as they land; fall back to a humanised id.
    else -> id.value.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** The per-provider subtitle: where the bytes live, or that the provider is coming later (#210). */
@Composable
private fun storageProviderNote(option: StorageProviderOption): String? = when (option.availability) {
    StorageProviderAvailability.ComingLater -> stringResource(Res.string.settings_storage_note_coming_later)
    StorageProviderAvailability.Available -> when (option.id) {
        StorageProviderId.OnDevice -> stringResource(Res.string.settings_storage_note_on_device)
        StorageProviderId.DefernoBackend -> stringResource(Res.string.settings_storage_note_deferno_backend)
        else -> null
    }
}

private val ThemeFamily.label: String
    @Composable get() = when (this) {
        ThemeFamily.Deferno -> stringResource(Res.string.settings_theme_family_deferno)
        ThemeFamily.Mono -> stringResource(Res.string.settings_theme_family_mono)
    }

private val ThemeMode.label: String
    @Composable get() = when (this) {
        ThemeMode.Light -> stringResource(Res.string.settings_theme_mode_light)
        ThemeMode.Dark -> stringResource(Res.string.settings_theme_mode_dark)
        ThemeMode.Auto -> stringResource(Res.string.settings_theme_mode_follow_system)
    }

/** The selectable done-visibility windows the View offers (seconds match the contract fixture). */
private enum class DoneVisibilityWindow(val label: StringResource, val seconds: Long?) {
    OneDay(Res.string.settings_done_visibility_one_day, 86400L),
    ThreeDays(Res.string.settings_done_visibility_three_days, 259200L),
    OneWeek(Res.string.settings_done_visibility_one_week, 604800L),
    Always(Res.string.settings_done_visibility_always, null),
}
