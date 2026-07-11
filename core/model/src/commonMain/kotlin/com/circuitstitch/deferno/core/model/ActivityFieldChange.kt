package com.circuitstitch.deferno.core.model

/**
 * One field's old->new change in a recorded edit (#260 follow-up) — the typed unit the Activity detail
 * sheet and the Task Trail render. Derived at read-time from the activity ledger's captured before/after
 * JSON; [field] is the typed code the View localizes (never a raw wire key), [rawKey] preserves the
 * original JSON key for [ActivityField.Unknown] fallbacks, and [before]/[after] are the two sides.
 */
data class ActivityFieldChange(
    val field: ActivityField,
    val rawKey: String,
    val before: ActivityFieldValue,
    val after: ActivityFieldValue,
)

/**
 * The typed identity of a changed field — mapped from the wire key so the View picks a localized label
 * (and the right value formatting: a date for [Deadline], a status label for [Status], …). [Unknown]
 * covers keys with no first-class row (a View may hide these); the caller still has [ActivityFieldChange.rawKey].
 */
enum class ActivityField {
    Title,
    Description,
    Deadline,
    Labels,
    Status,
    Pinned,
    Unknown,
    ;

    companion object {
        /** Map a captured wire key (the ledger body/before JSON keys) to its typed field. */
        fun fromKey(key: String): ActivityField = when (key) {
            "title" -> Title
            "description", "notes" -> Description
            "complete_by", "deadline" -> Deadline
            "labels" -> Labels
            "status" -> Status
            "pinned" -> Pinned
            else -> Unknown
        }
    }
}

/**
 * One side (old or new) of a field change. [Present] carries the raw value the View formats per [field]
 * (plain text for title/description; an RFC3339 instant for a deadline; the wire status token; "true"/
 * "false" for pinned; a comma-joined list for labels). [Cleared] is an explicit empty (a wire null or an
 * emptied list) the View renders as "—"/"none". [Unavailable] means the value wasn't captured (e.g. an
 * un-hydrated description's old body, or any writer that records no pre-image) — distinct from "was empty".
 */
sealed interface ActivityFieldValue {
    data class Present(val raw: String) : ActivityFieldValue
    data object Cleared : ActivityFieldValue
    data object Unavailable : ActivityFieldValue
}
