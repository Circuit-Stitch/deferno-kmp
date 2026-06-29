package com.circuitstitch.deferno.core.data.task

/**
 * A pre-applied filter/sort the global-search overlay can open with (#311) — the payload behind a
 * deep-link into Search (e.g. Settings → Storage "biggest attachments"). The overlay seeds these onto its
 * initial state and runs the search immediately, so the person lands on results, not an empty box. Lives
 * in `core:data` (next to [TaskSearchQuery]) so the shell route and the Settings output can both name it
 * without depending on `feature:tasks`.
 *
 * @property hasAttachment open with the "has attachment" filter on.
 * @property sort open with this sort applied.
 */
data class SearchSeed(
    val hasAttachment: Boolean = false,
    val sort: SearchSort = SearchSort.Relevance,
)
