package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Stable identifier of a [BrainDumpDraft] — a client-minted id (the on-device Extractor's draft id),
 * not a backend UUID: a draft never reaches the server as a draft. Like every other entity id it is a
 * faithful, non-blank [String] (cf. [TaskId]).
 */
@JvmInline
value class BrainDumpDraftId(val value: String) {
    init {
        require(value.isNotBlank()) { "BrainDumpDraftId must not be blank" }
    }
}

/**
 * A proposed draft Task awaiting user review, produced on-device from the Brain dump transcript by the
 * Extractor (ADR-0027, propose-only). It is **ephemeral and client-only**: persisted locally so it
 * survives the worker→review round-trip, and it leaves the store only when [Accepted] (committed
 * through the ordinary online Task create) or [Dismissed]. So it omits everything a synced cache row
 * carries — no orgSlug, hydration, or tombstone — keeping just the flat fields a created Task needs.
 */
data class BrainDumpDraft(
    val id: BrainDumpDraftId,
    val title: String,
    val notes: String? = null,
    val completeBy: LocalDate? = null,
    val deadlineTimeOfDay: LocalTime? = null,
    val status: BrainDumpDraftStatus = BrainDumpDraftStatus.Ready,
    val createdAt: Instant,
)

/** Where one [BrainDumpDraft] is in its review lifecycle. */
enum class BrainDumpDraftStatus {
    /** Persisted by the worker, not yet acted on — the reviewable state. */
    Ready,

    /** Committed through the online Task create; pending removal from the draft store. */
    Accepted,

    /** Discarded by the user; pending removal from the draft store. */
    Dismissed,
}
