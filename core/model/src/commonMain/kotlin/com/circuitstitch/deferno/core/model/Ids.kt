package com.circuitstitch.deferno.core.model

import kotlin.jvm.JvmInline

/**
 * Stable identifier of a [Task] — the backend's UUID `id` (ADR-0001). It is the reconcile key:
 * a full-snapshot refresh upserts/removes rows by [TaskId], so it must be a faithful, non-blank
 * copy of the wire `id`. Distinct from the human-facing [Task.ref] (`{org_slug}-{sequence}`),
 * which can be absent on a freshly created row and is *not* an identity.
 */
@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskId must not be blank" }
    }
}

/**
 * An Org's UUID (`owner_org_id`, ADR-0002) — the within-Account ownership boundary every Item
 * carries. Present on full (hydrated) items; absent on list summaries. Modelled distinctly from
 * the [Task.orgSlug] (the short `u-e4h2qk` slug used in `ref`).
 */
@JvmInline
value class OrgId(val value: String) {
    init {
        require(value.isNotBlank()) { "OrgId must not be blank" }
    }
}
