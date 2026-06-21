package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.SearchState
import kotlinx.datetime.LocalDate

/**
 * The global Search overlay View (#73): a thin renderer of [SearchComponent]. It hosts the query
 * field, the date / status / tags filter chips, the sort control, and the results list (reusing
 * [TaskRow]), and forwards every interaction as an intent — no logic here (ADR-0007). The whole
 * surface is opaque (a [Surface] over the foreground Destination) and carries a Close affordance that
 * dismisses back to origin.
 *
 * Distinct from the in-place Tasks-list filter chips: this is the global search, a separate surface.
 */
@Composable
fun SearchScreen(component: SearchComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    SearchContent(
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

/** Stateless body — driven directly by interaction/screenshot tests with fixed inputs. */
@Composable
internal fun SearchContent(
    state: SearchState,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onStatusToggled: (WorkingState) -> Unit,
    onLabelToggled: (String) -> Unit,
    onDateRangeChanged: (from: LocalDate?, to: LocalDate?) -> Unit,
    onSortChanged: (SearchSort) -> Unit,
    onResultClicked: (TaskId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Edge-to-edge overlay (#73): the Surface paints under the system bars; the content insets past
    // them so the header clears the status-bar clock and the results clear the nav bar.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Eyebrow("DEEP SEARCH")
                    Text(
                        text = "Reach any tree",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }

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
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                    )
                    // A visible submit affordance — the IME Search key alone is undiscoverable (#73).
                    Button(onClick = onSubmit, enabled = state.canSearch && !state.isSearching) {
                        Text("Search")
                    }
                }

                StatusFilters(selected = state.statuses, onToggle = onStatusToggled)
                TagsFilter(selected = state.labels, onToggle = onLabelToggled)
                DateRangeFilter(from = state.fromDate, to = state.toDate, onChange = onDateRangeChanged)
                SortControl(selected = state.sort, onChange = onSortChanged)
                ActiveFilterChips(state)
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
                val label = workingStateLabel(status)
                FilterChip(
                    selected = status in selected,
                    onClick = { onToggle(status) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/**
 * The tags/label filter (#73): a small input to add a tag plus a removable chip per currently-selected
 * label. Adding a tag and tapping a selected chip both forward [onToggle] — the component owns the
 * toggle semantics (add if absent, remove if present), so this stays a thin renderer (ADR-0007).
 * Feeds [com.circuitstitch.deferno.core.data.task.TaskSearchQuery.labels].
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
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
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
 * [com.circuitstitch.deferno.core.data.task.TaskSearchQuery.fromDate]/[toDate]. A full Material
 * date-range picker is deferred; a blank or unparseable field is treated as "no bound" (`null`), so
 * the user can clear an end of the range by emptying it. Each edit forwards the current (from, to) pair.
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

/**
 * The active filters at a glance (#231): the chosen statuses, tags, and a date bound surfaced as calm
 * [TreeChip]s so a user sees what's narrowing their search without re-reading every control. Read-only —
 * the controls above own the toggling; this is a summary line. Hidden when nothing is selected.
 */
@Composable
private fun ActiveFilterChips(state: SearchState) {
    val chips = buildList {
        state.statuses.forEach { add(workingStateLabel(it)) }
        state.labels.forEach { add("#$it") }
        if (state.fromDate != null || state.toDate != null) {
            add("${state.fromDate?.toString() ?: "…"} → ${state.toDate?.toString() ?: "…"}")
        }
    }
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip -> TreeChip(text = chip, filled = true) }
    }
}

@Composable
private fun SearchResults(state: SearchState, onResultClicked: (TaskId) -> Unit) {
    when {
        state.results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionLabel(
                    text = if (state.results.size == 1) "1 TREE" else "${state.results.size} TREES",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(state.results, key = { it.id.value }) { task: Task ->
                SearchResultRow(task = task, onClick = { onResultClicked(task.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        state.isSearching -> LoadingStrip(label = "Searching…")
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
 * A re-skinned search result (#231): a leading [KindDot] (a search hit is a Task, so it wears the Task
 * colour), the title, the working-state badge, and a mono meta line for the ref + labels.
 *
 * ponytail: the design's breadcrumb "path to the tree" is omitted — a [Task] search result carries no
 * ancestry (no parent chain in [SearchState.results]); surfacing it would need a new query field. We show
 * the ref + labels instead, which the result does carry. Wire [Breadcrumb] here when ancestry lands.
 */
@Composable
private fun SearchResultRow(task: Task, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = "Open ${task.title}", onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindDot(
                color = kindColor(ItemKind.Task),
                modifier = Modifier.semantics { contentDescription = "task" },
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                val meta = buildList {
                    task.ref?.let { add(it) }
                    if (task.labels.isNotEmpty()) add(task.labels.joinToString(" ") { "#$it" })
                }
                if (meta.isNotEmpty()) MonoMeta(text = meta.joinToString("  ·  "))
            }
            WorkingStateBadge(task.workingState)
        }
    }
}

/** The plain label for a [SearchSort] option. */
private fun sortLabel(sort: SearchSort): String = when (sort) {
    SearchSort.Relevance -> "Best match"
    SearchSort.TitleAsc -> "Title (A–Z)"
    SearchSort.DeadlineAsc -> "Soonest due"
}
