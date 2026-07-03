package com.circuitstitch.deferno.feature.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_cancel
import com.circuitstitch.deferno.core.designsystem.resources.common_done
import com.circuitstitch.deferno.core.designsystem.resources.common_retry
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
import com.circuitstitch.deferno.core.designsystem.format.formatInstant
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.feature.settings.SecuritySettings
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import org.jetbrains.compose.resources.stringResource

/**
 * The **Security & 2FA** detail (#72 follow-through) — the native port of the web SecurityPane over
 * the same first-party contract. Two sections (the 2FA summary + this account's connected devices)
 * with the component's modal [SecuritySettings.Flow] rendered as dialogs over them. All state and
 * sequencing live in the component (ADR-0003) — this renders and forwards intents; the only local
 * state is the pre-confirm dialogs (disable / revoke), which mutate nothing until confirmed.
 *
 * Rendered inside [SettingsScreen]'s CategoryDetail scaffold; in its own file so the one-file-per-
 * detail pattern keeps `SettingsScreen.kt` a dispatcher rather than a monolith.
 */
@Composable
internal fun SecurityDetail(component: SettingsComponent) {
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
                    onClick = { copySensitive(context, secret) },
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
    val context = LocalContext.current
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
                    onClick = { copySensitive(context, codes.joinToString("\n")) },
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

/**
 * Copy a credential (TOTP secret, recovery codes) to the clipboard **flagged sensitive**, so the
 * Android 13+ clipboard preview masks it and it's excluded from OEM cross-device clipboard sync.
 * The framework `ClipboardManager` is used instead of Compose's because only `ClipDescription`
 * extras carry the flag. The key is the inlined String constant `ClipDescription.EXTRA_IS_SENSITIVE`,
 * written literally so lint doesn't read a false API-33 floor into it; older platforms ignore it.
 */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText(null, text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
