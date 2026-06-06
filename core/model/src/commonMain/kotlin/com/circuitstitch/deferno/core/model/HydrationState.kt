package com.circuitstitch.deferno.core.model

/**
 * How completely a cached row is filled in (ADR-0001, #21/#22). List endpoints return
 * **summaries** (no `description`/`owner_org_id`/history); single-item endpoints return the
 * **full** object. The local cache tracks which it holds so the UI can render a summary
 * immediately and trigger an on-demand hydrate (summary → full) when a Task is opened, and so a
 * full-snapshot refresh never *downgrades* an already-full row back to summary.
 */
enum class HydrationState {
    /** Only the fields a list endpoint returns are populated. */
    Summary,

    /** The full single-item payload has been fetched and stored. */
    Full,
}
