package com.circuitstitch.deferno.feature.braindumps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.feature.braindumps.InboxComponent

/**
 * The Android-native **Inbox** screen (ADR-0015 Inbox amendment), restyled to the "See the trees"
 * direction: a header + count, a `WAITING FOR YOU` band, the draft cards, and a calm footer. Lists the
 * Ready draft cards (or the empty state), commits one with **Add task** / drops one with **Dismiss**,
 * and surfaces an **Undo** snackbar after a dismiss (the dismiss is recoverable — nothing is deleted).
 */
@Composable
fun InboxScreen(component: InboxComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.recentlyDismissed?.id) {
        val dismissed = state.recentlyDismissed ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Dismissed “${dismissed.title}”",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) component.onUndoDismiss()
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (state.rows.isEmpty()) {
            EmptyInbox(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(InboxCardSpacing),
            ) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Inbox",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                            MonoMeta(text = draftCount(state.rows.size))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Review each one — add it as a task, or dismiss it. Nothing's deleted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "section") {
                    SectionLabel(text = "WAITING FOR YOU", modifier = Modifier.padding(horizontal = 20.dp))
                }

                items(state.rows, key = { it.draft.id.value }) { row ->
                    DraftCard(
                        draft = row.draft,
                        accepting = row.accepting,
                        note = row.note,
                        onAccept = { component.onAccept(row.draft.id) },
                        onDismiss = { component.onDismiss(row.draft.id) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                item(key = "footer") {
                    Text(
                        text = "Triage at your pace — nothing's lost.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.defernoColors.inkMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** "1 draft" / "N drafts" — the quiet count beside the header. */
private fun draftCount(n: Int): String = if (n == 1) "1 draft" else "$n drafts"
