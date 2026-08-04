package com.circuitstitch.deferno.feature.plan

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanRow
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Plan's ✦ suggestion rule (#375) — [suggestionOrder] and its head [suggestedTask], the one copy all
 * four surfaces read (Android, desktop, iPhone, Mac).
 *
 * These live in `commonTest` rather than beside a View, and what that buys is specific. They **compile**
 * against every target the function ships to — `jvm`, the KMP Android library, `iosArm64`,
 * `iosSimulatorArm64`, `macosArm64` (`deferno.kmp`'s target set) — and in CI they **run** on the JVM and
 * the Android host, which `:feature:plan:ui`'s render tests never reached: that module is Android + JVM
 * and keeps its tests in `jvmTest` alone. On a macOS host `./gradlew check` runs them on the Apple
 * targets too; on the Linux runner CI actually uses (`.github/workflows/ci.yml` — `ubuntu-latest`,
 * `./gradlew check :koverVerify`) those tasks self-disable (ADR-0006), a gap `.github/workflows/macos.yml`
 * names outright and says #368 tracks. `ios.yml`/`macos.yml` run `xcodebuild … test` — the XCTest bundles
 * — and never a Gradle Apple test task.
 *
 * They are **not** the only tests those four surfaces have: both Apple apps carry XCTest bundles
 * (`app/iosApp/iosAppTests`, `app/macosApp/macosAppTests`) and the Compose body has `:feature:plan:ui`'s
 * render tests, which stayed there with the screen they exercise. What no Swift test can do is exercise
 * *this rule*: `Task.id` is a Kotlin value class Kotlin/Native erases, so a `Task` cannot be constructed
 * from Swift at all (`app/macosApp/macosAppTests/PriorityTests.swift` records exactly that). Hoisting the
 * precedence into Kotlin is what made it testable anywhere.
 *
 * The load-bearing guarantee they defend together: priority moves only the *suggestion*, never the Plan's
 * own order. The day list is what the person arranged — `:feature:plan:ui`'s
 * `planList_keepsTheCuratedOrder_whenAFireTaskIsPicked` pins that a Fire task lower down is suggested
 * **without** being hoisted up the list.
 */
class PlanSuggestionTest {

    /**
     * The ✦ for a day of rows, spelled exactly as each Plan body spells it — Compose's `PlanContent` with
     * `rows.mapNotNull { it.task }`, both `PlanView.swift` with `rows.compactMap(\.task)`. That projection
     * is the whole of what "the ✦ is Task-only" means now that there is no `List<PlanRow>` helper to hold
     * it, so it is written once here rather than inlined into each cross-kind case below.
     */
    private fun suggestedForDay(rows: List<PlanRow>): Task? = suggestedTask(rows.mapNotNull { it.task })

    // --- the pick ---

    @Test
    fun suggested_prefersAFireTaskOverAPinnedOne() {
        // #375: Fire outranks pinned. The person marking something urgent is the stronger "start here"
        // signal than having parked it at the top of the plan.
        val picked = suggestedTask(
            listOf(
                task("1", "Water the plants", pinned = true),
                task("2", "Call the plumber", priority = Priority.Fire),
            ),
        )

        assertEquals(TaskId("2"), picked?.id)
    }

    @Test
    fun suggested_fallsBackToPinned_thenToTheFirstInThePlan() {
        // With no Fire task the pre-existing precedence is untouched: pinned first, else whatever the
        // person put at the top.
        assertEquals(
            TaskId("2"),
            suggestedTask(
                listOf(task("1", "Water the plants"), task("2", "Call the plumber", pinned = true)),
            )?.id,
        )
        assertEquals(
            TaskId("1"),
            suggestedTask(listOf(task("1", "Water the plants"), task("2", "Call the plumber")))?.id,
        )
        assertNull(suggestedTask(emptyList()))
    }

    @Test
    fun suggested_picksTheFirstFireTask_whenThereAreSeveral() {
        // Within the Fire bucket the person's own order still decides — we don't re-rank inside it.
        val picked = suggestedTask(
            listOf(
                task("1", "Water the plants"),
                task("2", "Call the plumber", priority = Priority.Fire),
                task("3", "File the taxes", priority = Priority.Fire),
            ),
        )

        assertEquals(TaskId("2"), picked?.id)
    }

    @Test
    fun suggested_ignoresBacklog_whichSinksButStaysVisible() {
        // Backlog is a ranking bucket, not a filter: a Backlog task is never suggested over its peers,
        // but it is still in the plan and still the fallback when it is all there is.
        assertEquals(
            TaskId("2"),
            suggestedTask(
                listOf(
                    task("1", "Someday idea", priority = Priority.Backlog),
                    task("2", "Call the plumber", priority = Priority.Fire),
                ),
            )?.id,
        )
        assertEquals(
            TaskId("1"),
            suggestedTask(listOf(task("1", "Someday idea", priority = Priority.Backlog)))?.id,
        )
    }

    // --- the pick, and finished work (#375 review) ---

    /**
     * A finished task is never what to start next. It keeps whatever priority bucket it had, and the Plan
     * does render terminal rows (`observeActive()` filters tombstones, not states), so an unguarded Fire
     * arm answers "start here" with something already done.
     *
     * This is the guard the **#375 review** landed on both Apple views and not on Compose: until the rule
     * became one shared function, Android and desktop picked a different row than iPhone and Mac for the
     * same day. It is now impossible to fix on one platform only, but the case still needs pinning —
     * deleting the guard is a one-line edit, and this is what fails when someone makes it.
     */
    @Test
    fun suggested_neverStartsWithAFinishedFireTask() {
        val picked = suggestedTask(
            listOf(
                task("1", "Call the plumber", priority = Priority.Fire, workingState = WorkingState.Done),
                task("2", "Water the plants"),
            ),
        )

        assertEquals(TaskId("2"), picked?.id, "the open task, not the finished Fire one")
    }

    @Test
    fun suggested_skipsFinishedWorkAtTheFallbackTierToo() {
        // Not just the Fire lane: the plain "first in the plan" arm is guarded as well, so a day whose
        // first row is done still suggests the first row you could actually pick up.
        assertEquals(
            TaskId("2"),
            suggestedTask(
                listOf(
                    task("1", "Yesterday's leftovers", workingState = WorkingState.Dropped),
                    task("2", "Water the plants"),
                ),
            )?.id,
        )
    }

    @Test
    fun suggested_stillReturnsARowWhenEverythingIsFinished() {
        // The final arm is deliberately unguarded: a fully-finished day keeps its banner rather than
        // silently losing it. Losing the ✦ on the one day you cleared the plan would read as a bug, not
        // as praise.
        assertEquals(
            TaskId("1"),
            suggestedTask(
                listOf(
                    task("1", "Water the plants", workingState = WorkingState.Done),
                    task("2", "Call the plumber", workingState = WorkingState.Done),
                ),
            )?.id,
        )
    }

    /**
     * The `pinned` arm is deliberately NOT terminal-guarded, unlike the Fire arm above it. Pinning this
     * down stops a well-meaning "fix" from quietly re-opening the divergence the test above closes — the
     * rule is now one function, so changing this changes it on all four platforms at once, which is
     * exactly why the deliberate quirk needs a test rather than a comment.
     */
    @Test
    fun suggested_keepsTheUnguardedPinnedArm_soAPinnedDoneTaskStillOutranksAnOpenOne() {
        assertEquals(
            TaskId("1"),
            suggestedTask(
                listOf(
                    task("1", "Water the plants", pinned = true, workingState = WorkingState.Done),
                    task("2", "Call the plumber"),
                ),
            )?.id,
        )
    }

    // --- the pick, across a cross-kind day (#385) ---

    /**
     * The ✦ is Task-only. Its verb is "Start", and starting is what you do to a Task — a Habit is a
     * commitment you keep, not work you pick up, and tapping through leads to Focus mode, which is
     * Task-shaped end to end. The plan became cross-kind in #385, so a day's rows reach the precedence
     * through their Task projection and the recurring rows simply are not candidates.
     */
    @Test
    fun suggested_picksTheTaskRowUsingTheSamePrecedence() {
        val picked = suggestedForDay(
            listOf(
                recurringRow("h1", ItemKind.Habit, "Take a Walk"),
                taskRow("1", "Water the plants", pinned = true),
                taskRow("2", "Call the plumber", priority = Priority.Fire),
            ),
        )

        assertEquals(TaskId("2"), picked?.id, "the Fire Task, chosen past the Habit row entirely")
    }

    @Test
    fun suggested_isNullWhenTheDayHoldsNothingStartable() {
        // A plan of nothing but recurring rows gets no ✦ — the honest outcome rather than a suggestion
        // whose "Start" button leads to a Focus mode that cannot render it.
        assertNull(
            suggestedForDay(
                listOf(
                    recurringRow("h1", ItemKind.Habit, "Take a Walk"),
                    recurringRow("c1", ItemKind.Chore, "Take shot"),
                ),
            ),
        )
        assertNull(suggestedForDay(emptyList()))
    }

    // --- the order behind the pick ---

    /**
     * The whole of [suggestionOrder], not just its head. Every position is user-visible: both Apple
     * `WhatNextView`s render `candidates[index % count]` and their "Something else" button steps that
     * index, so the second and third ideas are what the person is offered next.
     */
    @Test
    fun order_offersFireThenPinnedThenStillOpenThenTheRest() {
        val day = listOf(
            task("done", "Yesterday's leftovers", workingState = WorkingState.Done),
            task("open", "Water the plants"),
            task("pinned-done", "Book the dentist", pinned = true, workingState = WorkingState.Done),
            task("fire", "Call the plumber", priority = Priority.Fire),
        )

        assertEquals(
            listOf("fire", "pinned-done", "open", "done"),
            suggestionOrder(day).map { it.id.value },
        )
    }

    /**
     * A task that qualifies for two passes is offered **once**, in the earlier one. `Task.id` is the
     * identity, matching what the Swift `candidates` property de-duplicated on before it delegated here:
     * `stableKey` is `BridgeKt.taskKey(task:)`, which unwraps that same id.
     */
    @Test
    fun order_offersATaskOnce_inItsEarliestPosition() {
        val day = listOf(
            task("1", "Water the plants"),
            task("2", "Call the plumber", priority = Priority.Fire, pinned = true),
        )

        assertEquals(
            listOf("2", "1"),
            suggestionOrder(day).map { it.id.value },
            "the Fire+pinned task appears once, in the Fire position",
        )
    }

    @Test
    fun order_keepsThePlansOwnOrderInsideEachPass() {
        // No re-ranking within a pass: the two Fire tasks are offered in the order the person put them
        // in, and so are the two pinned ones behind them. Same reason the pick isn't a sort.
        val day = listOf(
            task("f1", "Call the plumber", priority = Priority.Fire),
            task("p1", "Water the plants", pinned = true),
            task("f2", "File the taxes", priority = Priority.Fire),
            task("p2", "Book the dentist", pinned = true),
        )

        assertEquals(listOf("f1", "f2", "p1", "p2"), suggestionOrder(day).map { it.id.value })
    }

    @Test
    fun order_isEmptyForADayWithNothingInIt() {
        // No ideas at all: the head is null so the ✦ banner disappears, and the Apple sheet has nothing
        // to cycle through — which is the empty state it already guards for.
        assertEquals(emptyList<Task>(), suggestionOrder(emptyList()))
    }

    /**
     * [suggestedTask] is the head of [suggestionOrder] — by construction, since one is defined as the
     * other, but pinned here across every day the precedence tests above cover so that a future edit
     * giving either its own arms fails loudly instead of re-opening a divergence inside one file.
     */
    @Test
    fun suggested_isTheHeadOfTheOrder_onEveryDayThePrecedenceTestsCover() {
        val days: Map<String, List<Task>> = mapOf(
            "an empty day" to emptyList(),
            "Fire outranks pinned" to listOf(
                task("1", "Water the plants", pinned = true),
                task("2", "Call the plumber", priority = Priority.Fire),
            ),
            "pinned, with no Fire task" to listOf(
                task("1", "Water the plants"),
                task("2", "Call the plumber", pinned = true),
            ),
            "no signal at all" to listOf(task("1", "Water the plants"), task("2", "Call the plumber")),
            "several Fire tasks" to listOf(
                task("1", "Water the plants"),
                task("2", "Call the plumber", priority = Priority.Fire),
                task("3", "File the taxes", priority = Priority.Fire),
            ),
            "Backlog sinks but stays" to listOf(
                task("1", "Someday idea", priority = Priority.Backlog),
                task("2", "Call the plumber", priority = Priority.Fire),
            ),
            "a Backlog task alone" to listOf(task("1", "Someday idea", priority = Priority.Backlog)),
            "a finished Fire task" to listOf(
                task("1", "Call the plumber", priority = Priority.Fire, workingState = WorkingState.Done),
                task("2", "Water the plants"),
            ),
            "a finished first row" to listOf(
                task("1", "Yesterday's leftovers", workingState = WorkingState.Dropped),
                task("2", "Water the plants"),
            ),
            "an all-finished day" to listOf(
                task("1", "Water the plants", workingState = WorkingState.Done),
                task("2", "Call the plumber", workingState = WorkingState.Done),
            ),
            "a pinned finished task" to listOf(
                task("1", "Water the plants", pinned = true, workingState = WorkingState.Done),
                task("2", "Call the plumber"),
            ),
            "a cross-kind day's Task projection" to listOf(
                recurringRow("h1", ItemKind.Habit, "Take a Walk"),
                taskRow("1", "Water the plants", pinned = true),
                taskRow("2", "Call the plumber", priority = Priority.Fire),
            ).mapNotNull { it.task },
        )

        days.forEach { (day, tasks) ->
            assertEquals(
                suggestionOrder(tasks).firstOrNull()?.id,
                suggestedTask(tasks)?.id,
                "the ✦ must be the first idea offered — $day",
            )
        }
    }
}
