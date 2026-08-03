package com.circuitstitch.deferno.core.designsystem.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * A **test override** for the reference "today"; `null` (the default) means "read the live system clock".
 * A screenshot/UI test pins a fixed date for its subtree with
 * `CompositionLocalProvider(LocalToday provides LocalDate(…))` so date-driven goldens don't drift with the
 * wall clock. Read [currentToday] from a Composable rather than this local directly — it applies the
 * live-clock fallback. The default is a plain constant, so it is never the frozen wall clock (see below).
 */
val LocalToday = staticCompositionLocalOf<LocalDate?> { null }

/**
 * The reference **"today"** for date-relative UI — the Plan header's day, the Task-detail WHEN row's
 * "in N days", the Trail's TODAY divider. Returns the [LocalToday] override when a host (a test) pinned
 * one, otherwise the live system clock read **fresh on each recomposition** so production "today" advances
 * across midnight instead of freezing.
 *
 * The fallback deliberately does NOT live in [LocalToday]'s default factory: `staticCompositionLocalOf`
 * memoizes that factory once per process (a `LazyValueHolder`), which would freeze "today" at first read.
 * Reading the clock here — in a `@ReadOnlyComposable` getter re-run each recomposition — keeps it live,
 * matching the per-recomposition clock reads this seam replaced.
 */
val currentToday: LocalDate
    @Composable @ReadOnlyComposable
    get() = currentToday(TimeZone.currentSystemDefault())

/**
 * "Today" resolved in an explicit [zone] — for a reading that also resolves an *instant* to a day and
 * must do both in the same zone.
 *
 * Take this overload rather than pairing the [currentToday] property with a separate zone argument. A
 * caller holding two independent values has no way to keep them coupled, and the failure is silent: pass
 * a non-device zone (which is exactly what #392 will do once an account zone exists) and "today" quietly
 * stays resolved in the device's, shifting every reading by a day for anyone near a date boundary. Here
 * the zone is the only input, so the two cannot come apart.
 *
 * The [LocalToday] test override still wins, and is zone-agnostic by design: a test pinning the day is
 * pinning the *answer*, not asking for it to be recomputed somewhere else.
 */
@Composable
@ReadOnlyComposable
fun currentToday(zone: TimeZone): LocalDate = LocalToday.current ?: Clock.System.todayIn(zone)
