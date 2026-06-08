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
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent

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

    when (val child = stack.active.instance) {
        SettingsComponent.SettingsChild.List ->
            SettingsListContent(onOpenCategory = component::openCategory, modifier = modifier)

        is SettingsComponent.SettingsChild.Detail ->
            CategoryDetail(category = child.category, settings = settings, component = component, modifier = modifier)
    }
}

// --- category list (root) ---

@Composable
internal fun SettingsListContent(
    onOpenCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(title = "Settings", onBack = null)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SettingsCategory.entries.forEach { category ->
                CategoryRow(
                    label = category.title,
                    summary = if (category.backed) null else "Coming soon",
                    onClick = { onOpenCategory(category) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
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
    component: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DetailHeader(title = category.title, onBack = component::onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (category) {
                SettingsCategory.Appearance -> AppearanceDetail(settings, component)
                SettingsCategory.TaskBehavior -> TaskBehaviorDetail(settings, component)
                SettingsCategory.DataPrivacy -> DataPrivacyDetail(settings, component)
                SettingsCategory.HelpFeedback -> HelpFeedbackDetail()
                SettingsCategory.AppPermissions -> AppPermissionsDetail(component)
                SettingsCategory.Legal -> LegalDetail()
                SettingsCategory.Account -> AccountDetail(settings, component)
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
private fun TaskBehaviorDetail(settings: UserSettings, component: SettingsComponent) {
    ToggleRow(
        label = "Drag and drop (experimental)",
        description = "Reorder tasks by dragging. This is still being polished.",
        checked = settings.dragAndDropEnabled,
        onCheckedChange = component::onDragAndDropChanged,
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
        text = "Export and import are available from the Deferno web app. " +
            "We’ll bring them into the app in a future release.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
}

@Composable
private fun HelpFeedbackDetail() {
    Text(
        text = "Have a question or a suggestion? Send us feedback from the Deferno web app — " +
            "in-app feedback is coming soon.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
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
private fun LegalDetail() {
    SectionLabel("Terms of Service")
    Text(
        text = "By using Deferno you agree to our Terms of Service and acceptable-use policy.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    SectionLabel("Privacy")
    Text(
        text = "Your data stays yours. We never sell it. See the full privacy policy on the web.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
    SectionLabel("Open source")
    Text(
        text = "Deferno is built on open-source software, with thanks to its authors.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.defernoColors.inkMuted,
    )
}

@Composable
private fun AccountDetail(settings: UserSettings, component: SettingsComponent) {
    LabeledValue(label = "Username", value = settings.username ?: "—")
    LabeledValue(label = "Time zone", value = settings.timeZone ?: "Device default")
    TextButton(
        onClick = component::onOpenProfile,
        modifier = Modifier.heightIn(min = MinTouchTarget),
    ) { Text("View profile") }
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
private fun DetailHeader(title: String, onBack: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

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

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- View-only labels (kept out of the shared domain/registry, like the nav-suite labels) ---

private val SettingsCategory.title: String
    get() = when (this) {
        SettingsCategory.Appearance -> "Appearance"
        SettingsCategory.TaskBehavior -> "Task behavior"
        SettingsCategory.DataPrivacy -> "Data & Privacy"
        SettingsCategory.HelpFeedback -> "Help & Feedback"
        SettingsCategory.AppPermissions -> "App Permissions"
        SettingsCategory.Legal -> "Legal"
        SettingsCategory.Account -> "Account"
        SettingsCategory.Security2FA -> "Security & 2FA"
        SettingsCategory.Integrations -> "Integrations"
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
