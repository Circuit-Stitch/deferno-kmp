package com.circuitstitch.deferno.feature.plan

import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task

/**
 * The **one** "which task do we gently suggest starting with" rule (#375), expressed as an *order* so
 * that both shapes it is asked for come from here: the ✦ banner at the top of the Plan takes this list's
 * head ([suggestedTask]), and the Apple What's-next sheet — whose "Something else" button steps an index
 * into it — walks the whole of it.
 *
 * Four passes over the day, appended in precedence order and then de-duplicated by [Task.id] with the
 * first position winning:
 *
 * 1. still-open [Priority.Fire] work,
 * 2. pinned,
 * 3. anything still open,
 * 4. everything, in plan order.
 *
 * A task that qualifies twice — Fire *and* pinned — is therefore offered once, in its Fire place, and
 * each pass keeps the person's own order inside it.
 *
 * **Fire leads** because the person already said *this one is urgent*; asking them about something calmer
 * first would be the helper second-guessing them. [Priority.Backlog] gets **no** pass of its own and is
 * deliberately not filtered out either: the bottom bucket sinks, it never disappears (#375), so a
 * backlogged task arrives through the later passes like anything else and is still offered once the
 * earlier ideas are exhausted.
 *
 * **This orders the suggestion only.** The Plan list itself stays exactly as the person arranged it —
 * Compose's `PlanContent` and both `PlanView.swift` twins render the curated day in its own order and
 * merely mark the ✦ row; `:feature:plan:ui`'s `planList_keepsTheCuratedOrder_whenAFireTaskIsPicked` pins
 * that a Fire task lower down is suggested **without** being hoisted up the list.
 *
 * **Why the rule lives in the slice's Compose-free logic module.** `:feature:plan` is exported into
 * *both* Apple frameworks (`export(project(":feature:plan"))` in `app/iosApp/build.gradle.kts` and
 * `app/macosApp/build.gradle.kts`) and is also the module `:feature:plan:ui` renders from, so Android,
 * desktop, iPhone and Mac all answer "start here" from this one function — the shape the shared
 * recurrence reading took for the same reason (#384: `:feature:tasks`' `recurrenceReading` and the
 * `recurrenceLineTokens(item:)` seam both Apple apps read it through).
 *
 * What it replaced was this precedence hand-written **five times, in two languages**: Compose's
 * `PlanDashboard.kt` (`List<Task>.suggestedTask()`), `suggested(_:)` in each Apple `PlanView.swift`, and
 * the `candidates` property in each Apple `PlanExtras.swift` — kept in step by nothing but comments
 * saying they mirrored one another.
 *
 * It had already gone wrong. The #375 review's terminal guard landed on the Swift copies and never
 * reached the Compose one, which was still a three-armed `Fire ?: pinned ?: first`, so a day of
 * `[Fire+Done, Open]` answered "start here" with a *finished* task on Android and desktop — Start pill
 * and all — while iPhone and Mac picked the open one. Same account, same day, two answers. That was
 * repaired by hand one commit before this file existed; one copy is what stops the next drift, since
 * there is no longer a second place a fix can fail to reach.
 *
 * The Swift copies were also the ones nobody could measure — but **not** for want of a test target. Both
 * Apple apps carry real XCTest bundles of four files each: `app/iosApp/iosAppTests` (product type
 * `com.apple.product-type.bundle.unit-test` in `iosApp.xcodeproj`) and `app/macosApp/macosAppTests`
 * (`type: bundle.unit-test` in `app/macosApp/project.yml`), and `.github/workflows/ios.yml` +
 * `macos.yml` run both with `xcodebuild … test` on every pull request. The copies were untestable *in
 * place*: as `app/macosApp/macosAppTests/PriorityTests.swift` records, `Task.id` is a Kotlin value class
 * that Kotlin/Native erases, so a `Task` cannot be constructed from Swift at all, and "the row/candidate
 * logic that consumes one is exercised by the shared `feature/tasks` + `feature/plan` suites instead".
 * Hoisting the rule into Kotlin is what makes it testable at all — and `app/` sits outside the coverage
 * gate besides (`deferno.coverage.aggregation` aggregates `:core:`/`:feature:` paths only), so nothing
 * would have reported the copies as unmeasured either.
 *
 * **A plain top-level function, not a `List<Task>` extension.** Swift calls it as
 * `PlanSuggestionKt.suggestionOrder(tasks:)`, and a non-extension top-level function has a predictable
 * Objective-C name where an extension's receiver arrives under a label the Kotlin source never spells.
 * The house idiom for a Swift-facing seam already, cf. `recurrenceLineTokens(item:)` and
 * `taskTimeLabel(task:)`.
 *
 * [tasks] is a plain list of Tasks rather than the day's `PlanRow`s, because its callers rank different
 * things. The Plan bodies hand over the day's whole Task projection —
 * `rows.mapNotNull { it.task }` in Compose's `PlanContent`, `rows.compactMap(\.task)` in both
 * `PlanView.swift` (and on to `WhatNextView`) — but Compose's `WhatsNextContent` deliberately narrows
 * further, to `tasks.take(3)`: it draws three cards and resolves its selection against them, so a
 * suggestion from below the fold came back null and left the screen with no ✦ chip and a dead primary
 * button (#375 review; pinned by `:feature:plan:ui`'s
 * `whatsNext_suggestsFromTheThreeCardsItDraws_notTheWholeDay`). A `PlanRow` parameter would also move the
 * "the ✦ is Task-only" decision (#385) in here, where the What's-next surfaces — which are handed Tasks
 * and never see rows — could not call it; that narrowing is stated at each call site instead.
 */
fun suggestionOrder(tasks: List<Task>): List<Task> =
    (
        // **Open work only in the Fire lane.** A finished task keeps whatever priority bucket it had, and
        // the Plan does render terminal rows (`observeActive()` filters tombstones, not states), so an
        // unguarded Fire pass promotes a Done item to the very first suggestion (#375 review).
        tasks.filter { it.priority == Priority.Fire && !it.workingState.isTerminal } +
            // Deliberately NOT terminal-guarded, unlike the pass above: a pinned finished task still
            // outranks an unpinned open one. The pin is an explicit "keep this one in front of me" and the
            // ✦ honours it either way. It has its own test, so "surely this guard was just missed too" is
            // a change someone has to make on purpose — and it now moves all four surfaces at once, which
            // is the point.
            tasks.filter { it.pinned } +
            tasks.filter { !it.workingState.isTerminal } +
            // Deliberately bare: a fully-finished day keeps its banner rather than silently losing it.
            // Losing the ✦ on the one day you cleared the plan would read as a bug, not as praise.
            tasks
        )
        // First position wins, which is the identity the Swift `candidates` property de-duplicated on:
        // `stableKey` is `BridgeKt.taskKey(task:)`, and that unwraps this same `Task.id`.
        .distinctBy { it.id }

/**
 * The single task the ✦ suggests: the head of [suggestionOrder] — the first **open** [Priority.Fire]
 * task, else the first pinned one, else the first open one, else simply the first.
 *
 * Identical, input for input, to the four-arm `?:` chain it replaced. Appending the four passes and
 * taking the head yields the first element of the first non-empty pass (de-duplication never removes a
 * list's head, since the head has no earlier occurrence to lose to), and `tasks.filter(p).firstOrNull()`
 * is `tasks.firstOrNull(p)` — so this is exactly
 * `firstOrNull { openFire } ?: firstOrNull { pinned } ?: firstOrNull { open } ?: firstOrNull()`, empty
 * list and all-terminal day included. `suggested_isTheHeadOfTheOrder_onEveryDayThePrecedenceTestsCover`
 * holds the two together so they cannot drift.
 */
fun suggestedTask(tasks: List<Task>): Task? = suggestionOrder(tasks).firstOrNull()
