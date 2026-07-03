package com.circuitstitch.deferno.feature.settings.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.circuitstitch.deferno.core.designsystem.resources.common_done
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
import com.circuitstitch.deferno.core.designsystem.resources.settings_coming_soon_assistant_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_coming_soon_integrations_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_coming_soon_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_export_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_export_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_export_menu_export
import com.circuitstitch.deferno.core.designsystem.resources.settings_data_export_menu_full_backup
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
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_account_removal_email_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_account_removal_email_subject
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_open_source_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_open_source_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_privacy_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_privacy_policy_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_privacy_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_terms_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_terms_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_view_privacy_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_legal_view_terms_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_permissions_intro
import com.circuitstitch.deferno.core.designsystem.resources.settings_permissions_open_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_privacy_analytics_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_privacy_analytics_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_row_summary_unavailable
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_2fa_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_action_failed
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_added
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_date_pattern
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_last_used
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_never_used
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_revoke
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_device_this
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_devices_empty
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_devices_section
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_disable_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_disable_confirm_action
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_disable_confirm_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_disable_confirm_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_email_backup_add
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_email_backup_off
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_email_backup_on
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_email_backup_remove
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enable_button
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_code_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_copy_key
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_key_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_open_app
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_verify
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_enroll_wrong_code
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_off_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_recovery_ack
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_recovery_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_recovery_copy
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_recovery_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_revoke_confirm_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_revoke_confirm_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_stepup_body
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_stepup_continue
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_stepup_password_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_stepup_title
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_stepup_wrong
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_totp_on
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_totp_replace
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_totp_replace_note
import com.circuitstitch.deferno.core.designsystem.resources.settings_security_unavailable_body
import com.circuitstitch.deferno.core.designsystem.resources.common_retry
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_engine_android_native
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_engine_automatic
import com.circuitstitch.deferno.core.designsystem.resources.settings_speech_engine_whisper_on_device
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
import com.circuitstitch.deferno.core.designsystem.resources.settings_task_behavior_shake_undo_description
import com.circuitstitch.deferno.core.designsystem.resources.settings_task_behavior_shake_undo_label
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_family_deferno
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_family_mono
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_mode_dark
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_mode_follow_system
import com.circuitstitch.deferno.core.designsystem.resources.settings_theme_mode_light
import com.circuitstitch.deferno.core.designsystem.resources.shell_destination_assistant
import com.circuitstitch.deferno.core.designsystem.resources.shell_signed_in
import com.circuitstitch.deferno.core.designsystem.format.formatInstant
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.core.speech.UnavailableReason
import com.circuitstitch.deferno.feature.settings.InferenceEngineSettings
import com.circuitstitch.deferno.feature.settings.SecuritySettings
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SpeechEngineSettings
import com.circuitstitch.deferno.feature.settings.StorageProviderSettings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Minimum height for a tappable row/control — design-principles.md "≥44–48dp" touch targets. */
private val MinTouchTarget = 48.dp

/**
 * The **Settings** Destination View (#72, ADR-0013 / ADR-0007 tier 3): a thin renderer of the
 * [SettingsComponent]'s drill-down (ADR-0003: holds no logic). It renders the category **list** at the
 * root and a **per-category detail** when one is open, switching on the component's [SettingsComponent.stack].
 *
 * Every wireframe category is listed; the backed ones are functional over the live [UserSettings],
 * the two unbacked ones (Security & 2FA, Integrations) open a gentle coming-soon stub (no dead taps,
 * ADR-0015). Appearance writes apply **live** because the same settings `Flow` drives the app theme.
 */
@Composable
fun SettingsScreen(component: SettingsComponent, modifier: Modifier = Modifier) {
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
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Edge-to-edge (ADR-0035 #2): the list scrolls under the system nav bar but pads its last row
            // clear of it.
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
    ) {
        SettingsCategory.entries.forEach { category ->
            // The device-local Speech engine row shows only when this device has a real engine (#93,
            // ADR-0018) — hidden on platforms whose engine hasn't landed yet (desktop/iOS, #94/#95).
            if (category == SettingsCategory.SpeechEngine && !speechEngine.available) return@forEach
            // The Agent row shows only when this device has an inference engine to offer (#150); the
            // cloud option itself reflects not-entitled, so it stays visible when entitlement is absent (AC2).
            if (category == SettingsCategory.Agent && !inferenceEngine.available) return@forEach
            // The Assistant enablement row is iOS-only in v1 (ADR-0040 defers the Android/desktop Views);
            // skip it here so the deferred surface never shows a non-functional row.
            if (category == SettingsCategory.Assistant) return@forEach
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
            // Edge-to-edge (ADR-0035 #2): the detail scrolls under the system nav bar but pads its last
            // control clear of it.
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (category) {
            SettingsCategory.Appearance -> AppearanceDetail(settings, component)
            SettingsCategory.TaskBehavior -> TaskBehaviorDetail(settings, component)
            SettingsCategory.SpeechEngine -> SpeechEngineDetail(speechEngine, component::onSpeechEngineSelected)
            SettingsCategory.Agent -> AgentDetail(inferenceEngine, component::onInferenceEngineSelected)
            // The Assistant enablement surface is iOS-only in v1 (ADR-0040); the row is skipped on Android,
            // so this detail is never reached — kept only to keep the `when` total.
            SettingsCategory.Assistant -> ComingSoonDetail(
                body = stringResource(Res.string.settings_coming_soon_assistant_body),
            )
            SettingsCategory.Storage -> {
                // The brain-dump retention toggle (#211) lives under Storage — recordings are on-device
                // attachments. Collected here so only the Storage detail subscribes.
                val keepRecordings by component.keepBrainDumpRecordings.collectAsState()
                StorageProviderDetail(
                    storageProvider,
                    component::onStorageProviderSelected,
                    keepRecordings,
                    component::onKeepBrainDumpRecordingsChanged,
                )
            }
            SettingsCategory.DataPrivacy -> DataPrivacyDetail(settings, component)
            SettingsCategory.HelpFeedback -> HelpFeedbackDetail(component)
            SettingsCategory.AppPermissions -> AppPermissionsDetail(component)
            SettingsCategory.Legal -> LegalDetail(component)
            SettingsCategory.Account -> AccountDetail(component)
            SettingsCategory.Security2FA -> SecurityDetail(component)

            SettingsCategory.Integrations -> ComingSoonDetail(
                body = stringResource(Res.string.settings_coming_soon_integrations_body),
            )
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
            // ponytail: Whisper's model download isn't set up yet — not selectable until it is.
            enabled = option.id != SpeechEngineId.Whisper,
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
    // Shake-to-undo (#230): device-local, default on; the confirm prompt is the accidental-fire safety, and
    // the snackbar + menu undo paths stay available whether this is on or off.
    val shakeToUndo by component.shakeToUndo.collectAsState()
    ToggleRow(
        label = stringResource(Res.string.settings_task_behavior_shake_undo_label),
        description = stringResource(Res.string.settings_task_behavior_shake_undo_description),
        checked = shakeToUndo,
        onCheckedChange = component::onShakeToUndoChanged,
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
        text = stringResource(Res.string.settings_data_export_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    // On-device export (#313, ADR-0041): save the Backup zip via the Storage Access Framework "Save to…"
    // picker — the Android idiom for "export a file" (the picker always offers Files/Drive/Downloads;
    // ACTION_SEND share sheets do not surface a save-to-files target). The picker returns a destination
    // URI; the zip is built on the shared side only once the person commits to a location, then streamed
    // out. The small menu mirrors the iOS action sheet: the wired Export + the Full backup teaser (later
    // slice). Replaces the old web-redirect (the host's [onOpenDataExportImport] is now Android-dead too).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val zip = component.buildBackupZip()
                context.contentResolver.openOutputStream(uri)?.use { it.write(zip) }
            }
        }
    }
    TextButton(
        onClick = { menuOpen = true },
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.settings_data_export_button)) }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.settings_data_export_menu_export)) },
            onClick = {
                menuOpen = false
                saveBackup.launch("deferno-backup.zip")
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.settings_data_export_menu_full_backup)) },
            enabled = false,
            onClick = {},
        )
    }
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
private fun AppPermissionsDetail(component: SettingsComponent) {
    Text(
        text = stringResource(Res.string.settings_permissions_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    TextButton(
        onClick = component::onOpenAppPermissions,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.settings_permissions_open_button)) }
}

@Composable
private fun LegalDetail(component: SettingsComponent) {
    val context = LocalContext.current
    // The hosted page presented in the in-app reader (null = none).
    var reader by remember { mutableStateOf<LegalPage?>(null) }
    // Resolved here in composable scope — the click lambdas below can't call stringResource.
    val termsTitle = stringResource(Res.string.settings_legal_terms_section)
    val privacyTitle = stringResource(Res.string.settings_legal_privacy_policy_title)
    val removalSubject = stringResource(Res.string.settings_legal_account_removal_email_subject)
    val removalBody = stringResource(Res.string.settings_legal_account_removal_email_body)
    SectionLabel(stringResource(Res.string.settings_legal_terms_section))
    Text(
        text = stringResource(Res.string.settings_legal_terms_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    TextButton(
        onClick = { reader = LegalPage(termsTitle, TermsUrl) },
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.settings_legal_view_terms_button)) }
    SectionLabel(stringResource(Res.string.settings_legal_privacy_section))
    Text(
        text = stringResource(Res.string.settings_legal_privacy_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    TextButton(
        onClick = { reader = LegalPage(privacyTitle, PrivacyUrl) },
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text(stringResource(Res.string.settings_legal_view_privacy_button)) }
    SectionLabel(stringResource(Res.string.settings_legal_open_source_section))
    Text(
        text = stringResource(Res.string.settings_legal_open_source_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )

    reader?.let { page ->
        LegalReaderDialog(
            page = page,
            onDismiss = { reader = null },
            // support@ → the in-app feedback form (matches iOS); close the reader first.
            onContact = {
                reader = null
                component.onOpenSubmitFeedback()
            },
            // accounts@ → the mail app with a prefilled account-removal request.
            onAccountRemoval = { openAccountRemovalEmail(context, removalSubject, removalBody) },
        )
    }
}

/** A hosted legal page to read in-app: its reader-bar title + URL. */
private data class LegalPage(val title: String, val url: String)

private const val TermsUrl = "https://www.defernowork.com/terms"
private const val PrivacyUrl = "https://www.defernowork.com/privacy"

/**
 * iOS-parity in-app reader for our hosted Terms/Privacy: a full-screen [Dialog] over a [LegalWebView]
 * that hides the site's `nav`/`footer` and flattens in-content links *before* the content is revealed —
 * no full-site flash. The two email links stay live: `accounts@` opens the mail app with a prefilled
 * account-removal request ([onAccountRemoval]); any other `mailto:` / Cloudflare `email-protection`
 * link (i.e. `support@`) routes to the in-app feedback form ([onContact]).
 */
@Composable
private fun LegalReaderDialog(
    page: LegalPage,
    onDismiss: () -> Unit,
    onContact: () -> Unit,
    onAccountRemoval: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp)
                        .heightIn(min = MinTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_done)) }
                }
                LegalWebView(
                    url = page.url,
                    onContact = onContact,
                    onAccountRemoval = onAccountRemoval,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// Hides the site chrome (`nav`/`footer`) and flattens every link *except* the contact emails to inert
// plain text — so the emails read as the only tappable thing. A CSS rule applies to nav/footer added
// later in the parse, so injecting at page-start covers the whole load. Mirrors the iOS user script.
private const val HideChromeJs =
    "var s=document.createElement('style');" +
        "s.textContent='nav,footer{display:none!important} " +
        "a:not([href^=\"mailto:\"]):not([href*=\"email-protection\"])" +
        "{color:inherit!important;text-decoration:none!important;pointer-events:none!important}';" +
        "document.documentElement.appendChild(s);"

/**
 * The [WebView] half of [LegalReaderDialog]. Kept invisible behind a spinner until the page finishes
 * and the chrome-hiding CSS is applied (so the user never sees the full site), then revealed. JS is on
 * so the page's Cloudflare email-protection links decode to real `mailto:`s. Tapped links are inert
 * (the CSS kills `pointer-events`); the email links are handed off in [shouldOverrideUrlLoading].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LegalWebView(
    url: String,
    onContact: () -> Unit,
    onAccountRemoval: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember(url) { mutableStateOf(true) }
    Box(modifier) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            view.evaluateJavascript(HideChromeJs, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(HideChromeJs, null)
                            loading = false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            // Let the page's own loads + decode scripts through; act only on user taps.
                            if (!request.hasGesture()) return false
                            val target = request.url
                            val address = if (target.scheme == "mailto") {
                                target.schemeSpecificPart.substringBefore('?').trim()
                            } else {
                                ""
                            }
                            return when {
                                address.startsWith("accounts@defernowork.com", ignoreCase = true) -> {
                                    onAccountRemoval()
                                    true
                                }
                                target.scheme == "mailto" || target.toString().contains("email-protection") -> {
                                    onContact()
                                    true
                                }
                                // Every other tapped link is inert (matches iOS).
                                else -> true
                            }
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = WebView::destroy,
            modifier = Modifier.fillMaxSize().alpha(if (loading) 0f else 1f),
        )
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

/** Open the mail app with a prefilled account-removal request — the `accounts@` link's destination.
 *  [subject]/[body] are resolved from resources in composable scope by the caller. */
private fun openAccountRemovalEmail(context: Context, subject: String, body: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:accounts@defernowork.com")).apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    // No mail app installed → no-op rather than crash.
    runCatching { context.startActivity(intent) }
}

/**
 * The Account category is an **account switcher** (#NN): the signed-in roster — tap the active account
 * (chevron) to open its Profile (where identity + sign-out live), tap another to switch to it — plus
 * "Add another account" (re-enters sign-in, keeping the others). Time zone moved to Profile too.
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

/**
 * The **Security & 2FA** detail (#72 follow-through) — the native port of the web SecurityPane over
 * the same first-party contract. Two sections (the 2FA summary + this account's connected devices)
 * with the component's modal [SecuritySettings.Flow] rendered as dialogs over them. All state and
 * sequencing live in the component (ADR-0003) — this renders and forwards intents; the only local
 * state is the pre-confirm dialogs (disable / revoke), which mutate nothing until confirmed.
 */
@Composable
private fun SecurityDetail(component: SettingsComponent) {
    val security by component.security.collectAsState()
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<ConnectedDevice?>(null) }

    SectionLabel(stringResource(Res.string.settings_security_2fa_section))
    when (val overview = security.overview) {
        SecuritySettings.Overview.Loading -> CircularProgressIndicator(Modifier.padding(vertical = 8.dp))
        SecuritySettings.Overview.Unavailable -> {
            Text(
                text = stringResource(Res.string.settings_security_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            TextButton(
                onClick = component::onSecurityRetry,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(stringResource(Res.string.common_retry)) }
        }
        is SecuritySettings.Overview.Ready -> if (overview.totpEnabled) {
            Text(
                text = stringResource(Res.string.settings_security_totp_on),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (overview.emailBackup) {
                        Res.string.settings_security_email_backup_on
                    } else {
                        Res.string.settings_security_email_backup_off
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            TextButton(
                onClick = {
                    if (overview.emailBackup) component.onRemoveEmailBackup() else component.onAddEmailBackup()
                },
                enabled = !security.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Text(
                    stringResource(
                        if (overview.emailBackup) {
                            Res.string.settings_security_email_backup_remove
                        } else {
                            Res.string.settings_security_email_backup_add
                        },
                    ),
                )
            }
            Text(
                text = stringResource(Res.string.settings_security_totp_replace_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            TextButton(
                onClick = component::onEnrollTotp,
                enabled = !security.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(stringResource(Res.string.settings_security_totp_replace)) }
            TextButton(
                onClick = { confirmDisable = true },
                enabled = !security.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Text(
                    text = stringResource(Res.string.settings_security_disable_button),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                text = stringResource(Res.string.settings_security_off_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            TextButton(
                onClick = component::onEnrollTotp,
                enabled = !security.busy,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text(stringResource(Res.string.settings_security_enable_button)) }
        }
    }
    if (security.lastActionFailed) {
        Text(
            text = stringResource(Res.string.settings_security_action_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel(stringResource(Res.string.settings_security_devices_section))
    when (val devices = security.devices) {
        SecuritySettings.Devices.Loading -> CircularProgressIndicator(Modifier.padding(vertical = 8.dp))
        SecuritySettings.Devices.Unavailable -> Text(
            text = stringResource(Res.string.settings_security_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        is SecuritySettings.Devices.Ready ->
            if (devices.devices.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_security_devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.defernoColors.inkMuted,
                )
            } else {
                devices.devices.forEach { device ->
                    ConnectedDeviceRow(
                        device = device,
                        isThisDevice = device.id == devices.activeTokenId,
                        revokeEnabled = !security.busy,
                        onRevoke = { confirmRevoke = device },
                    )
                }
            }
    }

    // --- the component-driven modal flow ---
    when (val flow = security.flow) {
        is SecuritySettings.Flow.StepUp -> StepUpDialog(
            wrongPassword = flow.wrongPassword,
            busy = security.busy,
            onSubmit = component::onStepUpSubmit,
            onDismiss = component::onStepUpDismiss,
        )
        is SecuritySettings.Flow.EnterCode -> EnrollDialog(
            secret = flow.enrollment.secret,
            uri = flow.enrollment.uri,
            wrongCode = flow.wrongCode,
            busy = security.busy,
            onSubmit = component::onEnrollCodeSubmit,
            onDismiss = component::onEnrollDismiss,
        )
        is SecuritySettings.Flow.RecoveryCodes -> RecoveryCodesDialog(
            codes = flow.codes,
            onAcknowledge = component::onRecoveryCodesAcknowledged,
        )
        null -> Unit
    }

    // --- local pre-confirm dialogs (mutate nothing until confirmed) ---
    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(Res.string.settings_security_disable_confirm_title)) },
            text = { Text(stringResource(Res.string.settings_security_disable_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisable = false
                        component.onDisableMfa()
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.settings_security_disable_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
    confirmRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(stringResource(Res.string.settings_security_revoke_confirm_title)) },
            text = { Text(stringResource(Res.string.settings_security_revoke_confirm_body, device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = null
                        component.onRevokeDevice(device.id)
                    },
                ) {
                    Text(
                        text = stringResource(Res.string.settings_security_device_revoke),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = null }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}

/** One connected device: name + added/last-used dates; "This device" instead of Sign out on itself. */
@Composable
private fun ConnectedDeviceRow(
    device: ConnectedDevice,
    isThisDevice: Boolean,
    revokeEnabled: Boolean,
    onRevoke: () -> Unit,
) {
    val datePattern = stringResource(Res.string.settings_security_device_date_pattern)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    Res.string.settings_security_device_added,
                    formatInstant(device.createdAt, datePattern),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
            Text(
                text = device.lastUsedAt?.let {
                    stringResource(Res.string.settings_security_device_last_used, formatInstant(it, datePattern))
                } ?: stringResource(Res.string.settings_security_device_never_used),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        }
        if (isThisDevice) {
            Text(
                text = stringResource(Res.string.settings_security_device_this),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.defernoColors.inkMuted,
            )
        } else {
            TextButton(onClick = onRevoke, enabled = revokeEnabled) {
                Text(stringResource(Res.string.settings_security_device_revoke))
            }
        }
    }
}

/** The step-up password sheet: the server's 403 freshness gate, resumed by the component on success. */
@Composable
private fun StepUpDialog(
    wrongPassword: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_security_stepup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_security_stepup_body))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.settings_security_stepup_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(stringResource(Res.string.settings_security_stepup_wrong)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = !busy && password.isNotEmpty(),
            ) { Text(stringResource(Res.string.settings_security_stepup_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

/**
 * TOTP enrollment: on a phone the person usually can't scan their own screen, so the primary
 * affordance is the `otpauth://` deep link into an installed authenticator app, with the shared
 * secret as a copyable manual-entry fallback — then the 6-digit code entry to verify.
 */
@Composable
private fun EnrollDialog(
    secret: String,
    uri: String,
    wrongCode: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_security_enroll_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_security_enroll_body))
                TextButton(
                    // No authenticator app installed → no-op rather than crash (same posture as the
                    // Legal mail-app hand-off); the manual key below remains the fallback.
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text(stringResource(Res.string.settings_security_enroll_open_app)) }
                Text(
                    text = stringResource(Res.string.settings_security_enroll_key_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.defernoColors.inkMuted,
                )
                Text(
                    text = secret,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(secret)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text(stringResource(Res.string.settings_security_enroll_copy_key)) }
                OutlinedTextField(
                    value = code,
                    onValueChange = { entered -> code = entered.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(Res.string.settings_security_enroll_code_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = wrongCode,
                    supportingText = if (wrongCode) {
                        { Text(stringResource(Res.string.settings_security_enroll_wrong_code)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code) },
                enabled = !busy && code.length == 6,
            ) { Text(stringResource(Res.string.settings_security_enroll_verify)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

/**
 * The one-shot recovery codes. Deliberately NOT dismissable (no outside-tap/back dismiss, no cancel):
 * the codes are shown exactly once, so the only exit is the explicit "I've saved these codes"
 * acknowledgment — the same forced-save posture as the web app's recovery screen.
 */
@Composable
private fun RecoveryCodesDialog(
    codes: List<String>,
    onAcknowledge: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* deliberately inert — explicit acknowledgment is the only exit */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(Res.string.settings_security_recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_security_recovery_body))
                codes.forEach { code ->
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text(stringResource(Res.string.settings_security_recovery_copy)) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .toggleable(value = acknowledged, onValueChange = { acknowledged = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Text(
                        text = stringResource(Res.string.settings_security_recovery_ack),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge, enabled = acknowledged) {
                Text(stringResource(Res.string.common_done))
            }
        },
    )
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

// --- shared atoms ---


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

/** A radio row that, unlike [ChoiceRow], carries an optional [note] subtitle (the engine availability). */
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
            // `enabled = false` (a cloud engine with no entitlement) makes the row inert — not selectable (AC2).
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Color.Unspecified else MaterialTheme.defernoColors.inkMuted,
            )
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
    enabled: Boolean = true,
) {
    Row(
        // The whole row is the toggle target (≥48dp), so tapping the label flips it too — and the
        // Switch defers its click to the row (onCheckedChange = null) so there is one click handler.
        // `enabled = false` (e.g. an Agent row with no entitlement) makes the whole row inert (AC2).
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange),
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
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
    // The Agent row reflects the selected engine — "Off" by default (#150).
    this == SettingsCategory.Agent -> when (inferenceEngine.selected) {
        InferenceEngineId.Off -> stringResource(Res.string.settings_agent_engine_off)
        else -> inferenceEngineLabel(inferenceEngine.selected)
    }
    // The Storage row reflects the selected provider — "On-device" by default (#210).
    this == SettingsCategory.Storage -> storageProviderLabel(storageProvider.selected)
    // `backed` is the cross-platform baseline (the SwiftUI bridges still stub Security & 2FA), but
    // THIS View renders the real screen for it — so no coming-soon subtext on Android.
    this == SettingsCategory.Security2FA -> null
    !backed -> stringResource(Res.string.settings_coming_soon_title)
    else -> null
}

/** The human label for an engine id (View concern, like the nav-suite labels) — `Automatic` leads the row. */
@Composable
private fun speechEngineLabel(id: SpeechEngineId): String = when (id) {
    SpeechEngineId.Automatic -> stringResource(Res.string.settings_speech_engine_automatic)
    SpeechEngineId.Whisper -> stringResource(Res.string.settings_speech_engine_whisper_on_device)
    SpeechEngineId.AndroidNative -> stringResource(Res.string.settings_speech_engine_android_native)
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
            // An absent optional fast path (a Sidecar engine never registers on Android today, but the
            // reason is platform-neutral) — permanent, never the transient "Preparing…".
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
