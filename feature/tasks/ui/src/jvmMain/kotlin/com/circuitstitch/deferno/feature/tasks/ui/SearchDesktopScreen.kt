package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.designsystem.component.KindDot
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.SessionExpiredBanner
import com.circuitstitch.deferno.core.designsystem.component.TreeChip
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.SearchState
import kotlinx.datetime.LocalDate

/**
 * The global Search overlay View, desktop edition (#86, ADR-0015/0017) — the desktop counterpart of
 * the Android `SearchScreen`, bringing desktop to parity with the Android Search overlay (#73). It is
 * a thin renderer of the shared, Compose-free [SearchComponent] (ADR-0007: holds no logic): the query
 * field, the status / tags / date-range / sort filter controls, the results list (reusing the slice's
 * shared [TaskRow] atom), and a Close affordance — forwarding every interaction as an intent.
 *
 * The whole surface is opaque (a [Surface] over the foreground Destination); the desktop Shell View
 * layers it above the foreground pane and dismisses it on Esc (shell back precedence). Search stays
 * **online-only and one-shot** — the component drives a suspend pull, never the live task list (ADR-0001)
 * — so the View just renders the three gentle empty/searching/no-matches states keyed off
 * `isSearching`/`hasSearched`/`results`. A result tap forwards [SearchComponent.onResultClicked]; the
 * shell opens it in the Tasks Destination and dismisses the overlay.
 *
 * Desktop affordance (#30): besides Enter (the IME "search" action), an explicit **Search** button runs
 * the query — desktop-class input rather than the phone keyboard's search key.
 */
@Composable
fun SearchDesktopScreen(component: SearchComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    SearchDesktopContent(
        state = state,
        onQueryChanged = component::onQueryChanged,
        onSubmit = component::onSubmit,
        onStatusToggled = component::onStatusToggled,
        onLabelToggled = component::onLabelToggled,
        onDateRangeChanged = component::onDateRangeChanged,
        onSortChanged = component::onSortChanged,
        onResultClicked = component::onResultClicked,
        onDismiss = component::onDismiss,
        modifier = modifier,
    )
}

/** Stateless body — driven directly by the render/screenshot test with fixed inputs and intent spies. */
@Composable
internal fun SearchDesktopContent(
    state: SearchState,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onStatusToggled: (WorkingState) -> Unit,
    onLabelToggled: (String) -> Unit,
    onDateRangeChanged: (from: LocalDate?, to: LocalDate?) -> Unit,
    onSortChanged: (SearchSort) -> Unit,
    onResultClicked: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }

            // Search is online-only and above the chrome, so it can't rely on the shell banner — a 401'd
            // search shows the re-auth prompt here too (#297). "Sign in again" closes the overlay.
            if (state.sessionExpired) SessionExpiredBanner(onSignIn = onDismiss)

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChanged,
                        label = { Text("Search tasks") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    // Explicit desktop affordance (#30): Enter submits too, but a visible Search button is
                    // the desktop-class control. Disabled below the 2-char floor (SearchState.canSearch).
                    Button(onClick = onSubmit, enabled = state.canSearch) { Text("Search") }
                }

                StatusFilters(selected = state.statuses, onToggle = onStatusToggled)
                TagsFilter(selected = state.labels, onToggle = onLabelToggled)
                DateRangeFilter(from = state.fromDate, to = state.toDate, onChange = onDateRangeChanged)
                SortControl(selected = state.sort, onChange = onSortChanged)
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))

            SearchResults(state = state, onResultClicked = onResultClicked)
        }
    }
}

/** The status filter chips (#73): tap to narrow results to selected [WorkingState]s. */
@Composable
private fun StatusFilters(
    selected: Set<WorkingState>,
    onToggle: (WorkingState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Status",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkingState.entries.forEach { status ->
                FilterChip(
                    selected = status in selected,
                    onClick = { onToggle(status) },
                    label = { Text(workingStateLabel(status)) },
                )
            }
        }
    }
}

/**
 * The tags/label filter (#73): a small input to add a tag plus a removable chip per currently-selected
 * label. Adding a tag and tapping a selected chip both forward [onToggle] — the component owns the
 * toggle semantics (add if absent, remove if present), so this stays a thin renderer (ADR-0007).
 */
@Composable
private fun TagsFilter(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val add = {
        val tag = draft.trim()
        if (tag.isNotEmpty()) {
            onToggle(tag)
            draft = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add a tag") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardActions = KeyboardActions(onDone = { add() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            TextButton(onClick = add) { Text("Add tag") }
        }
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEach { label ->
                    FilterChip(
                        selected = true,
                        onClick = { onToggle(label) },
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = "Remove tag $label" },
                    )
                }
            }
        }
    }
}

/**
 * The date-range filter (#73): two ISO-date (YYYY-MM-DD) inputs feeding
 * [com.circuitstitch.deferno.core.data.task.TaskSearchQuery.fromDate]/[toDate]. A blank or unparseable
 * field is treated as "no bound" (`null`), so the user can clear an end of the range by emptying it.
 */
@Composable
private fun DateRangeFilter(
    from: LocalDate?,
    to: LocalDate?,
    onChange: (from: LocalDate?, to: LocalDate?) -> Unit,
) {
    var fromText by remember { mutableStateOf(from?.toString() ?: "") }
    var toText by remember { mutableStateOf(to?.toString() ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Date range",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fromText,
                onValueChange = {
                    fromText = it
                    onChange(parseIsoDateOrNull(it), parseIsoDateOrNull(toText))
                },
                label = { Text("From (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = toText,
                onValueChange = {
                    toText = it
                    onChange(parseIsoDateOrNull(fromText), parseIsoDateOrNull(it))
                },
                label = { Text("To (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Parse an ISO `YYYY-MM-DD` string to a [LocalDate], or `null` if blank/malformed (treated as no bound). */
private fun parseIsoDateOrNull(text: String): LocalDate? =
    text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** The client-side sort control (#73): a chip per [SearchSort] option. */
@Composable
private fun SortControl(selected: SearchSort, onChange: (SearchSort) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Sort",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.defernoColors.inkMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchSort.entries.forEach { sort ->
                FilterChip(
                    selected = sort == selected,
                    onClick = { onChange(sort) },
                    label = { Text(sortLabel(sort)) },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(state: SearchState, onResultClicked: (SearchHit) -> Unit) {
    when {
        state.results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            items(state.results, key = { it.id }) { hit ->
                SearchHitRow(hit = hit, query = state.query, onClick = { onResultClicked(hit) })
                HorizontalDivider()
            }
        }
        state.isSearching -> LoadingStrip(label = "Searching…")
        // An expired session is shown by the banner above, not as a "couldn't reach the server" state (#297).
        state.sessionExpired -> Unit
        // A failed pull (offline / server error) is NOT "no matches" — say so (#73 follow-up).
        state.searchFailed -> EmptyState(
            title = "Search is unavailable",
            body = "Something went wrong reaching the server. Check your connection and try again.",
        )
        state.hasSearched -> EmptyState(
            title = "No matches",
            body = "Nothing matched your search. Try a different word or fewer filters.",
        )
        else -> EmptyState(
            title = "Search your tasks",
            body = "Type at least two characters to find tasks by title or description.",
        )
    }
}

/**
 * A kind-aware desktop search row (#231) — at parity with the Android [SearchResultRow]: a leading
 * [KindDot] in the hit's real kind colour, the title with the matched [query] highlighted (struck + muted
 * when terminal), a calm mono meta line (kind label + ref), and a trailing due date on non-terminal hits.
 */
@Composable
private fun SearchHitRow(hit: SearchHit, query: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open ${hit.title}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindDot(color = kindColor(hit.kind), modifier = Modifier.semantics { contentDescription = hit.kind.name.lowercase() })
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = highlightedTitle(hit.title, query),
                style = MaterialTheme.typography.titleMedium,
                // Blocked mutes (but doesn't strike) so it isn't mistaken for actionable (#292).
                color = if (hit.isTerminal || hit.blocked) MaterialTheme.defernoColors.inkMuted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (hit.isTerminal) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(kindLabel(hit.kind))
                hit.ref?.let { add(it) }
            }
            MonoMeta(text = meta.joinToString("  ·  "))
        }
        if (hit.blocked) {
            Spacer(Modifier.width(10.dp))
            TreeChip(text = "Blocked", filled = false, content = MaterialTheme.defernoColors.inkMuted, semanticLabel = "Blocked")
        }
        if (!hit.isTerminal) {
            hit.completeBy?.let { due ->
                Spacer(Modifier.width(10.dp))
                MonoMeta(text = due.toDisplayDate())
            }
        }
    }
}

/** The hit title with case-insensitive matches of [query] highlighted in the accent container colour. */
@Composable
private fun highlightedTitle(title: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(title)
    val hl = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.defernoColors.amberDeep,
    )
    return buildAnnotatedString {
        val lcTitle = title.lowercase()
        val lcQuery = q.lowercase()
        var start = 0
        while (true) {
            val idx = lcTitle.indexOf(lcQuery, start)
            if (idx < 0) {
                append(title.substring(start))
                break
            }
            append(title.substring(start, idx))
            withStyle(hl) { append(title.substring(idx, idx + q.length)) }
            start = idx + q.length
        }
    }
}

/** The plain label for a [SearchSort] option (a View concern, kept local). */
private fun sortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.Relevance -> "Best match"
    SearchSort.TitleAsc -> "Title (A–Z)"
    SearchSort.DeadlineAsc -> "Soonest due"
}

// [workingStateLabel] is the shared commonMain label (lifted with WorkingStateEditor, see TaskDetailContent).
