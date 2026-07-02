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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.launch

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
            .clickable(onClickLabel = "Open $label", onClick = onClick)
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
                body = "The Assistant is available on iPhone for now. It’s coming to Android soon.",
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
            SettingsCategory.Security2FA -> ComingSoonDetail(
                body = "Two-factor authentication and security are managed by your identity provider. " +
                    "We’ll bring these controls into the app soon.",
                action = "Open security console",
                onAction = component::onOpenConsole,
            )

            SettingsCategory.Integrations -> ComingSoonDetail(
                body = "Connect Deferno with the other tools you use. Integrations are on the way.",
            )
        }
    }
}

@Composable
private fun AppearanceDetail(settings: UserSettings, component: SettingsComponent) {
    SectionLabel("Theme")
    ThemeFamily.entries.forEach { family ->
        ChoiceRow(
            label = family.label,
            selected = settings.themeFamily == family,
            onSelect = { component.onThemeFamilyChanged(family) },
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel("Mode")
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
        text = "Choose how dictation turns your voice into text. This stays on this device and isn’t " +
            "synced to your account.",
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
        text = "The Agent can turn a brain dump into draft tasks and suggest changes to your plan. Choose " +
            "which engine it uses — or turn it off. An on-device engine keeps your text on this device; " +
            "Deferno’s cloud AI sends it off your device to generate proposals (you always review before " +
            "anything is saved). It’s off by default, and this choice stays on this device, not synced to " +
            "your account.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    // "Off" is always offered first (the default); then each engine registered on this device. A cloud
    // engine the Account isn't entitled to is shown **disabled** ("Premium"), never selectable (AC2).
    EngineChoiceRow(
        label = "Off",
        note = "The Agent stays off. Nothing is sent anywhere.",
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
        text = "Choose where your attachments are stored. On-device keeps the files on this device and " +
            "works offline. This choice stays on this device and isn’t synced to your account. (Feedback " +
            "you send the team is always uploaded to Deferno so we can see it.)",
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
        label = "Keep brain dump recordings",
        description = "Save the original voice recording on this device when you accept a brain dump, so you can listen back later.",
        checked = keepRecordings,
        onCheckedChange = onKeepRecordingsChange,
    )
}

@Composable
private fun TaskBehaviorDetail(settings: UserSettings, component: SettingsComponent) {
    ToggleRow(
        label = "Drag and drop (experimental)",
        description = "Reorder tasks by dragging. This is still being polished.",
        checked = settings.dragAndDropEnabled,
        onCheckedChange = component::onDragAndDropChanged,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    // Shake-to-undo (#230): device-local, default on; the confirm prompt is the accidental-fire safety, and
    // the snackbar + menu undo paths stay available whether this is on or off.
    val shakeToUndo by component.shakeToUndo.collectAsState()
    ToggleRow(
        label = "Shake to undo",
        description = "Shake your phone to undo the last task move. You’ll be asked to confirm first.",
        checked = shakeToUndo,
        onCheckedChange = component::onShakeToUndoChanged,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel("Done visibility")
    Text(
        text = "How long completed items stay visible.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    DoneVisibilityRow(
        label = "Everywhere",
        seconds = settings.globalDoneVisibilitySeconds,
        onSelect = { component.onDoneVisibilityChanged(it, settings.dashboardDoneVisibilitySeconds) },
    )
    DoneVisibilityRow(
        label = "On the dashboard",
        seconds = settings.dashboardDoneVisibilitySeconds,
        onSelect = { component.onDoneVisibilityChanged(settings.globalDoneVisibilitySeconds, it) },
    )
}

@Composable
private fun DataPrivacyDetail(settings: UserSettings, component: SettingsComponent) {
    ToggleRow(
        label = "Analytics & tracking",
        description = "Help improve Deferno by sharing anonymous usage data.",
        checked = settings.trackingEnabled,
        onCheckedChange = component::onTrackingChanged,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel("Your data")
    Text(
        text = "Export your tasks and lists as a backup file you can save or share.",
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
    ) { Text("Export your data") }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text("Export") },
            onClick = {
                menuOpen = false
                saveBackup.launch("deferno-backup.zip")
            },
        )
        DropdownMenuItem(
            text = { Text("Full backup — coming soon") },
            enabled = false,
            onClick = {},
        )
    }
}

@Composable
private fun HelpFeedbackDetail(component: SettingsComponent) {
    Text(
        text = "Have a question, a bug, or a suggestion? Send it straight to the Deferno team — " +
            "attach files if it helps.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    // Opens the in-app Feedback form overlay (#375) — the shell handles the submit, no web round-trip.
    TextButton(
        onClick = component::onOpenSubmitFeedback,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text("Send feedback") }
}

@Composable
private fun AppPermissionsDetail(component: SettingsComponent) {
    Text(
        text = "Manage Deferno’s notifications, storage, and other permissions in your device settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    TextButton(
        onClick = component::onOpenAppPermissions,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text("Open app settings") }
}

@Composable
private fun LegalDetail(component: SettingsComponent) {
    val context = LocalContext.current
    // The hosted page presented in the in-app reader (null = none).
    var reader by remember { mutableStateOf<LegalPage?>(null) }
    SectionLabel("Terms of Service")
    Text(
        text = "By using Deferno you agree to our Terms of Service and acceptable-use policy.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    TextButton(
        onClick = { reader = LegalPage("Terms of Service", TermsUrl) },
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text("View Terms of Service") }
    SectionLabel("Privacy")
    Text(
        text = "Your data stays yours. We never sell it. See the full privacy policy on the web.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    TextButton(
        onClick = { reader = LegalPage("Privacy Policy", PrivacyUrl) },
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text("View Privacy Policy") }
    SectionLabel("Open source")
    Text(
        text = "Deferno is built on open-source software, with thanks to its authors.",
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
            onAccountRemoval = { openAccountRemovalEmail(context) },
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
                    TextButton(onClick = onDismiss) { Text("Done") }
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

/** Open the mail app with a prefilled account-removal request — the `accounts@` link's destination. */
private fun openAccountRemovalEmail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:accounts@defernowork.com")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "Account removal request")
        putExtra(
            Intent.EXTRA_TEXT,
            "Hello,\n\n" +
                "I'd like to request removal of my Deferno account and its associated data.\n\n" +
                "Account email:\n\n" +
                "Thank you.",
        )
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

    SectionLabel("Signed in")
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
    ) { Text("Add another account") }
}

@Composable
private fun ComingSoonDetail(body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Text(
        text = "Coming soon",
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
                    label = window.label,
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
    get() = when (this) {
        SettingsCategory.Appearance -> "Appearance"
        SettingsCategory.TaskBehavior -> "Task behavior"
        SettingsCategory.SpeechEngine -> "Speech engine"
        SettingsCategory.Agent -> "Agent"
        SettingsCategory.Assistant -> "Assistant"
        SettingsCategory.Storage -> "Storage"
        SettingsCategory.DataPrivacy -> "Data & Privacy"
        SettingsCategory.HelpFeedback -> "Help & Feedback"
        SettingsCategory.AppPermissions -> "App Permissions"
        SettingsCategory.Legal -> "Legal"
        SettingsCategory.Account -> "Account"
        SettingsCategory.Security2FA -> "Security & 2FA"
        SettingsCategory.Integrations -> "Integrations"
    }

/**
 * The category-row summary line: the Speech engine row shows the current choice (and flags it when the
 * chosen engine isn't usable yet — AC #3); the unbacked categories show "Coming soon"; the rest show none.
 */
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
        if (unavailable) "$label · unavailable" else label
    }
    // The Agent row reflects the selected engine — "Off" by default (#150).
    this == SettingsCategory.Agent -> when (inferenceEngine.selected) {
        InferenceEngineId.Off -> "Off"
        else -> inferenceEngineLabel(inferenceEngine.selected)
    }
    // The Storage row reflects the selected provider — "On-device" by default (#210).
    this == SettingsCategory.Storage -> storageProviderLabel(storageProvider.selected)
    !backed -> "Coming soon"
    else -> null
}

/** The human label for an engine id (View concern, like the nav-suite labels) — `Automatic` leads the row. */
private fun speechEngineLabel(id: SpeechEngineId): String = when (id) {
    SpeechEngineId.Automatic -> "Automatic"
    SpeechEngineId.Whisper -> "Whisper (on-device)"
    SpeechEngineId.AndroidNative -> "Android (on-device)"
    // Future native fast paths get explicit labels as they land (#96/#97); fall back to a humanised id.
    else -> id.value.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** The per-engine subtitle: Automatic explains itself; a real engine shows *why* it isn't usable yet (AC #3). */
private fun speechEngineNote(option: SpeechEngineOption): String? = when (option.id) {
    SpeechEngineId.Automatic -> "Use the best engine available on this device"
    else -> when (val availability = option.availability) {
        SpeechAvailability.Available -> null
        is SpeechAvailability.Unavailable -> when (availability.reason) {
            UnavailableReason.ModelMissing -> "Downloading…"
            UnavailableReason.UnsupportedLocale -> "Not available for your language"
            UnavailableReason.NoEngine -> "Not available on this device"
            UnavailableReason.NotReady -> "Preparing…"
            // An absent optional fast path (a Sidecar engine never registers on Android today, but the
            // reason is platform-neutral) — permanent, never the transient "Preparing…".
            UnavailableReason.NotInstalled -> "Not available on this device"
        }
    }
}

/** The human label for an inference-engine id (View concern, like the speech-engine labels). */
private fun inferenceEngineLabel(id: InferenceEngineId): String = when (id) {
    InferenceEngineId.Off -> "Off"
    InferenceEngineId.DefernoCloud -> "Deferno cloud AI"
    // The zero-ML deterministic floor — distinct from the planned on-device-LLM hybrid (ADR-0027).
    InferenceEngineId.OnDeviceFloor -> "On-device basics"
    // Further on-device runtimes + a future BYO engine get explicit labels as they land; fall back to a humanised id.
    else -> id.value.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** The per-engine subtitle: where it runs, or *why* it isn't selectable yet (the premium upsell, AC2). */
private fun inferenceEngineNote(option: InferenceEngineOption): String? = when (option.availability) {
    InferenceEngineAvailability.RequiresPremium -> "Premium — not available for your account yet"
    InferenceEngineAvailability.Available -> when (option.origin) {
        InferenceEngineOrigin.OnDevice -> "Runs on this device"
        InferenceEngineOrigin.DefernoCloud -> "Sends your text off-device to Deferno’s hosted AI"
    }
}

/** The human label for a storage-provider id (View concern, like the engine labels). */
private fun storageProviderLabel(id: StorageProviderId): String = when (id) {
    StorageProviderId.OnDevice -> "On-device"
    StorageProviderId.DefernoBackend -> "Deferno backend"
    StorageProviderId.Dropbox -> "Dropbox"
    StorageProviderId.GoogleDrive -> "Google Drive"
    // Future user-owned providers get explicit labels as they land; fall back to a humanised id.
    else -> id.value.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** The per-provider subtitle: where the bytes live, or that the provider is coming later (#210). */
private fun storageProviderNote(option: StorageProviderOption): String? = when (option.availability) {
    StorageProviderAvailability.ComingLater -> "Coming later"
    StorageProviderAvailability.Available -> when (option.id) {
        StorageProviderId.OnDevice -> "Keeps files on this device; works offline"
        StorageProviderId.DefernoBackend -> "Stores files on Deferno’s servers"
        else -> null
    }
}

private val ThemeFamily.label: String
    get() = when (this) {
        ThemeFamily.Deferno -> "Deferno"
        ThemeFamily.Mono -> "Mono"
    }

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
        ThemeMode.Auto -> "Follow system"
    }

/** The selectable done-visibility windows the View offers (seconds match the contract fixture). */
private enum class DoneVisibilityWindow(val label: String, val seconds: Long?) {
    OneDay("1 day", 86400L),
    ThreeDays("3 days", 259200L),
    OneWeek("1 week", 604800L),
    Always("Always", null),
}
