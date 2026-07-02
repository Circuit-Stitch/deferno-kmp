package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.circuitstitch.deferno.core.designsystem.component.BlockedChip
import com.circuitstitch.deferno.core.designsystem.component.DefernoIcons
import com.circuitstitch.deferno.core.designsystem.component.KindDot
import com.circuitstitch.deferno.core.designsystem.component.MonoMeta
import com.circuitstitch.deferno.core.designsystem.component.PrimaryActionButton
import com.circuitstitch.deferno.core.designsystem.component.SectionLabel
import com.circuitstitch.deferno.core.designsystem.component.SegmentedFilter
import com.circuitstitch.deferno.core.designsystem.component.SessionExpiredBanner
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.common_back
import com.circuitstitch.deferno.core.designsystem.resources.common_labels
import com.circuitstitch.deferno.core.designsystem.resources.common_open_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_remove_named_cd
import com.circuitstitch.deferno.core.designsystem.resources.common_status_done
import com.circuitstitch.deferno.core.designsystem.resources.common_when
import com.circuitstitch.deferno.core.designsystem.resources.search_apply_filters
import com.circuitstitch.deferno.core.designsystem.resources.search_clear_a11y
import com.circuitstitch.deferno.core.designsystem.resources.search_filters
import com.circuitstitch.deferno.core.designsystem.resources.search_initial_body
import com.circuitstitch.deferno.core.designsystem.resources.search_initial_title
import com.circuitstitch.deferno.core.designsystem.resources.search_label_chip_format
import com.circuitstitch.deferno.core.designsystem.resources.search_no_matches_body
import com.circuitstitch.deferno.core.designsystem.resources.search_no_matches_title
import com.circuitstitch.deferno.core.designsystem.resources.search_placeholder_trees
import com.circuitstitch.deferno.core.designsystem.resources.search_reset
import com.circuitstitch.deferno.core.designsystem.resources.search_reset_filters_a11y
import com.circuitstitch.deferno.core.designsystem.resources.search_searching
import com.circuitstitch.deferno.core.designsystem.resources.search_section_status
import com.circuitstitch.deferno.core.designsystem.resources.search_sort_a11y
import com.circuitstitch.deferno.core.designsystem.resources.search_sort_best_match
import com.circuitstitch.deferno.core.designsystem.resources.search_sort_biggest_attachments
import com.circuitstitch.deferno.core.designsystem.resources.search_sort_soonest_due
import com.circuitstitch.deferno.core.designsystem.resources.search_sort_title_asc
import com.circuitstitch.deferno.core.designsystem.resources.search_when_any_time
import com.circuitstitch.deferno.core.designsystem.resources.search_when_overdue
import com.circuitstitch.deferno.core.designsystem.resources.search_when_this_week
import com.circuitstitch.deferno.core.designsystem.resources.tasks_detail_add_label_placeholder
import com.circuitstitch.deferno.core.designsystem.resources.tasks_filter_active
import com.circuitstitch.deferno.core.designsystem.resources.tasks_filter_all
import com.circuitstitch.deferno.core.designsystem.resources.tasks_tree_count
import com.circuitstitch.deferno.core.designsystem.theme.defernoColors
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.SearchState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The global Search overlay View ("Deep search", #73/#231): a thin renderer of [SearchComponent]. The
 * mock's "Find & move" layout — a back chevron + an amber-focused search field, a horizontal filter bar
 * (a "Filters" pill + removable active chips), the result-meta + sort, and **kind-aware** result rows —
 * with the filters tucked into a bottom sheet. Forwards every interaction as an intent (ADR-0007).
 *
 * Results are now kind-agnostic [SearchHit]s (#231): the server returns items of every kind, so a row
 * wears its real kind dot/label instead of being painted as a Task. Grove + breadcrumb stay omitted —
 * neither is on the search wire (a hit carries only structure + its kind).
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
    onDateRangeChanged: (from: kotlinx.datetime.LocalDate?, to: kotlinx.datetime.LocalDate?) -> Unit,
    onSortChanged: (SearchSort) -> Unit,
    onResultClicked: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFilters by remember { mutableStateOf(false) }

    // Edge-to-edge overlay: the Surface paints under the system bars; content insets past them.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            SearchHeader(query = state.query, onQueryChanged = onQueryChanged, onSubmit = onSubmit, onBack = onDismiss)
            // Search is online-only and sits above the chrome, so it can't rely on the shell banner — a
            // 401'd search shows the re-auth prompt here too (#297). "Sign in again" closes the overlay,
            // returning to the surface where account controls live.
            if (state.sessionExpired) SessionExpiredBanner(onSignIn = onDismiss)
            FilterBar(
                state = state,
                onOpenFilters = { showFilters = true },
                onRemoveStatuses = { setStatusPreset(emptySet(), state.statuses, onStatusToggled); onSubmit() },
                onRemoveLabel = { onLabelToggled(it); onSubmit() },
                onRemoveDates = { onDateRangeChanged(null, null); onSubmit() },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SearchResults(state = state, onResultClicked = onResultClicked, onSortChanged = onSortChanged)
        }
    }

    if (showFilters) {
        FilterSheet(
            state = state,
            onStatusToggled = onStatusToggled,
            onLabelToggled = onLabelToggled,
            onDateRangeChanged = onDateRangeChanged,
            onApply = { showFilters = false; onSubmit() },
            onDismissSheet = { showFilters = false },
        )
    }
}

/** The header: a back chevron + the amber-focused search field with a leading magnifier and a clear ×. */
@Composable
private fun SearchHeader(query: String, onQueryChanged: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = DefernoIcons.ChevronLeft,
            contentDescription = stringResource(Res.string.common_back),
            tint = MaterialTheme.defernoColors.amberDeep,
            modifier = Modifier
                .size(MinTouchTarget)
                .clip(CircleShape)
                .clickable(onClickLabel = stringResource(Res.string.common_back), onClick = onBack)
                .padding(10.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = { Text(stringResource(Res.string.search_placeholder_trees)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = {
                Icon(DefernoIcons.Search, contentDescription = null, tint = MaterialTheme.defernoColors.inkMuted)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.defernoColors.inkMuted,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClickLabel = stringResource(Res.string.search_clear_a11y)) { onQueryChanged("") }
                            .padding(horizontal = 8.dp),
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f),
        )
    }
}

/** The horizontal filter bar: a "Filters" pill (count badge → opens the sheet) + removable active chips. */
@Composable
private fun FilterBar(
    state: SearchState,
    onOpenFilters: () -> Unit,
    onRemoveStatuses: () -> Unit,
    onRemoveLabel: (String) -> Unit,
    onRemoveDates: () -> Unit,
) {
    val activeCount = (if (state.statuses.isNotEmpty()) 1 else 0) +
        state.labels.size +
        (if (state.fromDate != null || state.toDate != null) 1 else 0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FiltersPill(count = activeCount, onClick = onOpenFilters)
        if (state.statuses.isNotEmpty()) {
            RemovableChip(label = statusSummary(state.statuses), onRemove = onRemoveStatuses)
        }
        state.labels.forEach { label ->
            RemovableChip(label = stringResource(Res.string.search_label_chip_format, label), onRemove = { onRemoveLabel(label) })
        }
        if (state.fromDate != null || state.toDate != null) {
            RemovableChip(label = stringResource(Res.string.common_when), onRemove = onRemoveDates)
        }
    }
}

/** The amber "Filters" pill with a count badge (white disc, amber number) — opens the filter sheet. */
@Composable
private fun FiltersPill(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClickLabel = stringResource(Res.string.search_filters), onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = stringResource(Res.string.search_filters),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** A removable active-filter chip: its label + a × that clears it. */
@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .heightIn(min = 36.dp)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        val removeCd = stringResource(Res.string.common_remove_named_cd, label)
        Text(
            text = "×",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.defernoColors.inkMuted,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClickLabel = removeCd, onClick = onRemove)
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = removeCd },
        )
    }
}

/** The bottom-sheet filters (#231): STATUS (Active/Done/All) · WHEN presets · LABELS, with an Apply footer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    state: SearchState,
    onStatusToggled: (WorkingState) -> Unit,
    onLabelToggled: (String) -> Unit,
    onDateRangeChanged: (from: kotlinx.datetime.LocalDate?, to: kotlinx.datetime.LocalDate?) -> Unit,
    onApply: () -> Unit,
    onDismissSheet: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    ModalBottomSheet(onDismissRequest = onDismissSheet, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.search_filters),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(Res.string.search_reset),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.defernoColors.amberDeep,
                    modifier = Modifier.clickable(onClickLabel = stringResource(Res.string.search_reset_filters_a11y)) {
                        setStatusPreset(emptySet(), state.statuses, onStatusToggled)
                        state.labels.forEach(onLabelToggled)
                        onDateRangeChanged(null, null)
                    },
                )
            }

            // STATUS — Active / Done / All, mapped onto the WorkingState set. (The catalog stores the
            // sentence-case labels; the section headers render uppercased, as before.)
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                SectionLabel(stringResource(Res.string.search_section_status).uppercase())
                SegmentedFilter(
                    options = StatusPresets.map { stringResource(it.labelRes) },
                    selectedIndex = statusPresetIndex(state.statuses),
                    onSelect = { setStatusPreset(StatusPresets[it].statuses, state.statuses, onStatusToggled) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // WHEN — calm presets that set the date range (Custom… is deferred — no inline picker yet).
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                SectionLabel(stringResource(Res.string.common_when).uppercase())
                val whenPresets = listOf(
                    stringResource(Res.string.search_when_any_time) to (null to null),
                    stringResource(Res.string.search_when_this_week) to (today to today.plus(7, DateTimeUnit.DAY)),
                    stringResource(Res.string.search_when_overdue) to (null to today),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    whenPresets.forEach { (label, range) ->
                        val selected = state.fromDate == range.first && state.toDate == range.second
                        ChoiceChip(label = label, selected = selected) { onDateRangeChanged(range.first, range.second) }
                    }
                }
            }

            // LABELS — add a tag, plus a removable chip per selected label.
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                SectionLabel(stringResource(Res.string.common_labels).uppercase())
                LabelsEditor(selected = state.labels, onToggle = onLabelToggled)
            }

            PrimaryActionButton(text = stringResource(Res.string.search_apply_filters), onClick = onApply, icon = null)
        }
    }
}

/** A pill toggle used inside the sheet (WHEN presets): amber when [selected], calm surface otherwise. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = label, onClick = onClick)
            .heightIn(min = 38.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Add-a-tag field + a removable chip per selected label (feeds the search query's `labels`). */
@Composable
private fun LabelsEditor(selected: Set<String>, onToggle: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val add = {
        val tag = draft.trim()
        if (tag.isNotEmpty()) {
            onToggle(tag)
            draft = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(Res.string.tasks_detail_add_label_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardActions = KeyboardActions(onDone = { add() }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEach { label ->
                    RemovableChip(label = stringResource(Res.string.search_label_chip_format, label), onRemove = { onToggle(label) })
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    onResultClicked: (SearchHit) -> Unit,
    onSortChanged: (SearchSort) -> Unit,
) {
    when {
        state.results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            item { ResultMeta(count = state.results.size, sort = state.sort, onSortChanged = onSortChanged) }
            items(state.results, key = { it.id }) { hit ->
                SearchResultRow(hit = hit, query = state.query, onClick = { onResultClicked(hit) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        state.isSearching -> LoadingStrip(label = stringResource(Res.string.search_searching))
        // An expired session is shown by the banner above, not as a "couldn't reach the server" state (#297).
        state.sessionExpired -> Unit
        state.hasSearched -> EmptyState(
            title = stringResource(Res.string.search_no_matches_title),
            body = stringResource(Res.string.search_no_matches_body),
        )
        else -> EmptyState(
            title = stringResource(Res.string.search_initial_title),
            body = stringResource(Res.string.search_initial_body),
        )
    }
}

/** The result-meta line: a "N TREES" count and a tappable "Best match ▾" sort affordance (cycles options). */
@Composable
private fun ResultMeta(count: Int, sort: SearchSort, onSortChanged: (SearchSort) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The catalog stores the sentence-case "N trees" plural; this meta line renders it uppercased, as before.
        SectionLabel(text = pluralStringResource(Res.plurals.tasks_tree_count, count, count).uppercase())
        val next = SearchSort.entries[(SearchSort.entries.indexOf(sort) + 1) % SearchSort.entries.size]
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = stringResource(Res.string.search_sort_a11y, sortLabel(sort))) { onSortChanged(next) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = sortLabel(sort),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.defernoColors.amberDeep,
            )
            Icon(
                imageVector = DefernoIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.defernoColors.amberDeep,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A kind-aware search result (#231): a leading [KindDot] in the hit's real kind colour, the title with
 * the matched term highlighted, a calm mono meta line (kind label + ref), and a trailing due date. A
 * done (terminal) hit strikes + mutes its title. Breadcrumb/grove stay omitted (not on the search wire).
 */
@Composable
private fun SearchResultRow(hit: SearchHit, query: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = stringResource(Res.string.common_open_named_cd, hit.title), onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val kindCd = kindA11yLabel(hit.kind)
            KindDot(
                color = kindColor(hit.kind),
                modifier = Modifier.semantics { contentDescription = kindCd },
            )
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
                BlockedChip()
            }
            if (!hit.isTerminal) {
                hit.completeBy?.let { due ->
                    Spacer(Modifier.width(10.dp))
                    MonoMeta(text = due.toDisplayDate())
                }
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

/** The plain label for a [SearchSort] option. */
@Composable
private fun sortLabel(sort: SearchSort): String = stringResource(
    when (sort) {
        SearchSort.Relevance -> Res.string.search_sort_best_match
        SearchSort.TitleAsc -> Res.string.search_sort_title_asc
        SearchSort.DeadlineAsc -> Res.string.search_sort_soonest_due
        SearchSort.AttachmentSizeDesc -> Res.string.search_sort_biggest_attachments
    },
)

/** The three STATUS presets the sheet offers, each mapped onto a [WorkingState] set. */
private class StatusPreset(val labelRes: StringResource, val statuses: Set<WorkingState>)

private val StatusPresets = listOf(
    StatusPreset(Res.string.tasks_filter_active, setOf(WorkingState.Open, WorkingState.InProgress, WorkingState.InReview)),
    StatusPreset(Res.string.common_status_done, setOf(WorkingState.Done, WorkingState.Dropped)),
    StatusPreset(Res.string.tasks_filter_all, emptySet()),
)

/** Which STATUS preset the current set reads as (empty ⇒ All; the terminal set ⇒ Done; else Active). */
private fun statusPresetIndex(statuses: Set<WorkingState>): Int =
    StatusPresets.indexOfFirst { it.statuses == statuses }.takeIf { it >= 0 }
        ?: if (statuses.isEmpty()) 2 else 0

/** A short summary of the active STATUS chip (Active / Done / Status). */
@Composable
private fun statusSummary(statuses: Set<WorkingState>): String =
    StatusPresets.firstOrNull { it.statuses == statuses && it.statuses.isNotEmpty() }
        ?.let { stringResource(it.labelRes) }
        ?: stringResource(Res.string.search_section_status)

/**
 * Apply a STATUS [target] set through the single-toggle [onToggle] intent: flip exactly the statuses whose
 * membership differs. Keeps the component's narrow `onStatusToggled` API (no new set-setter needed).
 */
private fun setStatusPreset(target: Set<WorkingState>, current: Set<WorkingState>, onToggle: (WorkingState) -> Unit) {
    WorkingState.entries.forEach { ws -> if ((ws in target) != (ws in current)) onToggle(ws) }
}
