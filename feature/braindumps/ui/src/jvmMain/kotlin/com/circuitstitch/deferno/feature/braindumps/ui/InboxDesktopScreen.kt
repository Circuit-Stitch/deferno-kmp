package com.circuitstitch.deferno.feature.braindumps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.feature.braindumps.InboxComponent

/**
 * The desktop-native **Inbox** screen (ADR-0015 Inbox amendment): the desktop counterpart of
 * [InboxScreen], reusing the shared draft-card / empty-state atoms. The brain-dump capture is Android-
 * only in v1 (the on-device floor is Android-only, ADR-0027), so desktop typically shows the empty
 * state — but the Destination is real, so accept/dismiss work the moment a draft is present.
 */
@Composable
fun InboxDesktopScreen(component: InboxComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    if (state.rows.isEmpty()) {
        EmptyInbox(modifier.fillMaxSize())
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
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
