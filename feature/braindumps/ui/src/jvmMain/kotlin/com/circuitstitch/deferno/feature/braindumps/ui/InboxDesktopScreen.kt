package com.circuitstitch.deferno.feature.braindumps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_undo
import com.circuitstitch.deferno.core.designsystem.resources.inbox_dismissed_snackbar
import com.circuitstitch.deferno.core.designsystem.resources.inbox_draft_count
import com.circuitstitch.deferno.core.designsystem.resources.inbox_footer_reassurance
import com.circuitstitch.deferno.core.designsystem.resources.inbox_header_subtitle
import com.circuitstitch.deferno.core.designsystem.resources.inbox_section_waiting_caps
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.feature.braindumps.InboxComponent
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop-native **Inbox** screen (ADR-0015 Inbox amendment): the desktop counterpart of
 * [InboxScreen], reusing the shared draft-card / empty-state atoms and mirroring its calm framing —
 * a header + draft count, a `WAITING FOR YOU` band, the draft cards, and a reassuring footer. The
 * brain-dump capture is Android-only in v1 (the on-device floor is Android-only, ADR-0027), so desktop
 * typically shows the empty state — but the Destination is real, so accept/dismiss work the moment a
 * draft is present.
 *
 * Crucially the **dismiss is recoverable** here too: an Undo snackbar after a dismiss restores the
 * draft (the "nothing is deleted" contract, ADR-0015), wired to the same [InboxComponent.onUndoDismiss]
 * seam the Android screen uses.
 */
@Composable
fun InboxDesktopScreen(component: InboxComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val dismissedMessage = state.recentlyDismissed?.let { stringResource(Res.string.inbox_dismissed_snackbar, it.title) }
    val undoLabel = stringResource(Res.string.common_undo)
    LaunchedEffect(state.recentlyDismissed?.id) {
        val message = dismissedMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
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
                    // ponytail: title lives in the shell top bar (chromeFor → ForDestination(Inbox)); the body
                    // shows only the count + subtitle so "Inbox" isn't rendered twice. Matches Settings/Calendar.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        MonoMeta(text = draftCount(state.rows.size))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(Res.string.inbox_header_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item(key = "section") {
                    SectionLabel(text = stringResource(Res.string.inbox_section_waiting_caps), modifier = Modifier.padding(horizontal = 20.dp))
                }

                items(state.rows, key = { it.draft.id.value }) { row ->
                    DraftCard(
                        draft = row.draft,
                        accepting = row.accepting,
                        note = row.noteKind,
                        onAccept = { component.onAccept(row.draft.id) },
                        onDismiss = { component.onDismiss(row.draft.id) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                item(key = "footer") {
                    Text(
                        text = stringResource(Res.string.inbox_footer_reassurance),
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
@Composable
private fun draftCount(n: Int): String = pluralStringResource(Res.plurals.inbox_draft_count, n, n)
