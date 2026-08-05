package com.circuitstitch.deferno.core.model

/**
 * How a [Chore]'s schedule **advances after a firing is closed out** — the domain projection of the
 * wire's `cadence_mode` (`backend/src/models/chore.rs`, a `snake_case` serde enum;
 * `contracts/openapi-0.1.json` pins the closed two-token set `["rolling","fixed"]`).
 *
 * **A [CadenceMode] is not a [Cadence]**, and the near-collision is the wire's vocabulary rather than
 * ours. The [Recurrence]'s [Cadence] says *which days* a chore fires on; the mode says *what happens to
 * the next deadline once one of those firings is completed or dropped*. The two are orthogonal — any
 * cadence can carry either mode — and neither ever substitutes for the other.
 *
 * **It is load-bearing, not decoration.** On mark-done, when the head segment is [Rolling] *and* the
 * cadence has a stride expressible in days, the backend sets the next deadline to `done_at + n days` in
 * the user's zone, and says so outright: "This arm never consults the series — the interval IS the rule"
 * (`backend/src/handlers/occurrences.rs`). So on the two commonest cadences the mode decides whether
 * "next due" is a function of **when the user actually finished** or of the rule the user wrote:
 * [Rolling] restarts the clock from the completion, [Fixed] leaves the grid standing still and the next
 * firing is whatever the recurrence already said it would be, however late the last one was closed.
 * That is why this must be a read type and not a string nobody looks at — a client that renders or
 * projects a next-due date without knowing the mode is projecting the wrong date on half the chores.
 *
 * **Sealed rather than an `enum class`, and typed here rather than in the DTO.** [Unmodelled] mirrors
 * [Cadence.Unmodelled] (#382): a token a future backend adds survives verbatim through the cache and
 * the Backup file under its own name instead of being flattened into a default that destroys it. That
 * is also precisely why the DTO field stays a plain `String?` — `DefernoJson` sets
 * `coerceInputValues = true` (ADR-0005), which silently rewrites an unrecognised *enum* token to the
 * property's default, so a `@Serializable enum` here would eat the unknown token before any mapper
 * could preserve it. The typing happens in the mappers; the wire stays a string.
 */
sealed interface CadenceMode {

    /**
     * The next deadline is measured **from the completion** — "I did it today, so it is due again in
     * `n` days". The backend's `#[default]`, and therefore the mode of every chore that has never said
     * otherwise; see [cadenceModeFromWire] for why an absent token is this rather than an unknown.
     */
    data object Rolling : CadenceMode

    /**
     * The next deadline is whatever the [Recurrence] says it is, regardless of when (or whether) the
     * last firing was closed out — the grid does not shift because the user was late.
     */
    data object Fixed : CadenceMode

    /**
     * A mode this build cannot model; the wire token survives verbatim (#382) so an unrenderable mode
     * round-trips through the cache and the Backup file under its own name rather than silently
     * becoming [Rolling] and changing how the user's chore schedules.
     *
     * Both modes the backend defines are named above, so this only fires on a future third — at which
     * point the fix is to model it, not to widen this. [cadenceModeFromWire] never produces a blank
     * [rawType]: a blank/absent token is [Rolling], the wire's own reading of "not stated".
     */
    data class Unmodelled(val rawType: String) : CadenceMode
}

/**
 * The wire `cadence_mode` token -> [CadenceMode]; the one place a mode is read, so the "absent means
 * Rolling" rule is stated once instead of at each of the six call sites that touch the field.
 *
 * **An absent, null or blank token IS [CadenceMode.Rolling] — not an unknown.** The backend field is
 * `#[serde(default)]` over a `#[default] Rolling` variant with no `skip_serializing_if`, so the server
 * both defaults a missing token to rolling and always writes one back out; a boot migration
 * (`backend/src/migrations/chore_backfill_cadence_mode_rolling.rs`) re-persisted every legacy row so the
 * field is explicit on disk too. The same holds from the local side: **every** client-created chore has
 * a NULL column, because no client code has ever set one. Reading absence as [CadenceMode.Unmodelled]
 * would therefore mislabel the entire local cache as unknown-mode, which is exactly the failure the
 * degrade-don't-throw readers elsewhere in this model avoid (compare `RecurrenceBound.Never`, where the
 * absent `end` key is likewise the correct reading and not merely a safe fallback).
 *
 * Anything else is a token some newer client or server named and this build has never heard of; the
 * token is all there is to keep, so it becomes the name.
 */
fun cadenceModeFromWire(token: String?): CadenceMode = when (token) {
    // Absent / null / blank and the explicit token are the SAME reading — see above.
    null, "", WIRE_ROLLING -> CadenceMode.Rolling
    WIRE_FIXED -> CadenceMode.Fixed
    else -> CadenceMode.Unmodelled(token)
}

/**
 * [CadenceMode] -> the wire token, the inverse of [cadenceModeFromWire] and the **only** spelling any
 * write side may emit — the create payload, the Backup file's `items.json` and the SQLite
 * `chore.cadence_mode` column all take this and never the Kotlin variant name.
 *
 * That matters in three separate ways. The column is a **persisted format, not a display string** (the
 * same warning `RecurringEntityCodec` carries for `recurrence_type`): every cached row already holds the
 * literal `rolling`/`fixed`/NULL a previous build wrote straight through from the wire, so emitting
 * `"Rolling"` would strip the mode off every one of them on the next read — and because the token is
 * already the wire's, no migration is needed and none should be written. `items.json` **is** the API's
 * own snake-case JSON (ADR-0041), so any renaming or re-casing here corrupts a restore. And the create
 * payload goes straight back to a server whose `CadenceMode` is a closed two-token enum.
 *
 * [CadenceMode.Rolling] emits its token explicitly rather than omitting the key: that is what the server
 * itself serializes (it has no `skip_serializing_if`) and what the backfill migration wrote to disk, so
 * an explicit `rolling` is the canonical encoding, not a widening. `null` is reachable only from a
 * hand-built [CadenceMode.Unmodelled] with a blank token — which [cadenceModeFromWire] never produces —
 * and omitting the key there is the safe residual: the reader on either end fills in [CadenceMode.Rolling],
 * the one value a nameless mode could not have contradicted.
 */
val CadenceMode.wireToken: String?
    get() = when (this) {
        CadenceMode.Rolling -> WIRE_ROLLING
        CadenceMode.Fixed -> WIRE_FIXED
        is CadenceMode.Unmodelled -> rawType.ifBlank { null }
    }

/**
 * The wire tokens, exactly as `backend/src/models/chore.rs` spells them (`#[serde(rename_all =
 * "snake_case")]`) and as `contracts/openapi-0.1.json` pins them. Also the **stored** tokens: the
 * `chore.cadence_mode` column has always held the raw wire string, so these two constants are a
 * persisted format on both boundaries at once and renaming either is a data-loss change.
 */
private const val WIRE_ROLLING = "rolling"
private const val WIRE_FIXED = "fixed"
