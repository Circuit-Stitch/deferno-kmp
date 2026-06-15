package com.circuitstitch.deferno.feature.braindumps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.feature.braindumps.InboxComponent

/**
 * The Android-native **Inbox** screen (ADR-0015 Inbox amendment): a thin render of [InboxComponent].
 * Lists the Ready draft cards (or the empty state), commits one with **Add task** / drops one with
 * **Dismiss**, and surfaces an **Undo** snackbar after a dismiss (the dismiss is recoverable — nothing
 * is deleted).
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(InboxCardSpacing),
            ) {
                items(state.rows, key = { it.draft.id.value }) { row ->
                    DraftCard(
                        draft = row.draft,
                        accepting = row.accepting,
                        note = row.note,
                        onAccept = { component.onAccept(row.draft.id) },
                        onDismiss = { component.onDismiss(row.draft.id) },
                    )
                }
            }
        }
    }
}
