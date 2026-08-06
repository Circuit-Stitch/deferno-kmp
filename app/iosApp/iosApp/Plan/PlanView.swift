import Deferno
import SwiftUI

/// The Plan Destination host (#51) — a **tier-3 drill-down** (`PlanChild`: Dashboard ↔ Detail(task)).
/// A Plan tap pushes the Task's detail onto the Plan stack; a subtask drill pushes deeper. The whole
/// stack renders inline inside the shell chrome (the drawer stays live) — no shell overlay any more (the
/// detail used to be a `.sheet`). The single adaptive shell bar (`MainShellView`) titles each surface and
/// drives back, so the detail is hosted header-less; mirrors macOS's `PlanHostView`.
struct PlanHostView: View {
    let plan: MainShellComponentDestinationChildPlan
    @StateObject private var stack: StateFlowObserver<MainShellComponentPlanChild>

    init(plan: MainShellComponentDestinationChildPlan) {
        self.plan = plan
        _stack = StateObject(wrappedValue: StateFlowObserver(plan.activeChild))
    }

    var body: some View {
        let child = stack.value
        if let dashboard = ShellBridgeKt.planChildDashboard(child: child) {
            PlanView(component: dashboard)
        } else if let detail = ShellBridgeKt.planChildDetail(child: child) {
            // Header-less: the shell bar shows the Task title + ← back (which pops via the shell's onBack).
            TaskDetailView(component: detail, showsHeader: false).id(BridgeKt.detailKey(component: detail))
        }
    }
}

/// The daily Plan pane (#51) restyled to the "See the trees" direction — the app's calm home
/// (design-principles.md: "open into today's Plan, not the whole backlog"). A thin renderer of
/// [PlanComponent]: observes today's ordered rows and forwards taps (open the Task) / refresh.
///
/// Mirrors the Android `PlanScreen` restyle: a hero header (Brandmark + "Today" + date + a gentle count
/// subtitle), a "IF YOU'RE NOT SURE, START HERE" suggestion banner, a "YOUR DAY" list of `CheckDot` rows,
/// an "Add from the forest" dashed footer, and a "See everything ›" link with an attention count.
///
/// **The day holds items of any kind (#385).** `PlanState.rows` is `[PlanRow]` — an `Item` plus, for a
/// Task and only a Task, its concrete `Task`. The plan used to be read from `/tasks/plan`, whose handler
/// resolves the day's ordered ids against the server's Task store alone: a day of one Habit and one
/// Chore came back as `[]` and this pane rendered blank. Everything Task-shaped here reads `row.task`
/// and is simply absent on a recurring row — see `dayRow`.
///
/// What's-next / Focus are **derived client-side from the rows' Task projection** (PlanState carries only
/// `{ rows, isRefreshing }`) — exactly as `PlanScreen.kt` derives them. The suggestion card presents the
/// decision-helper + focus surfaces as sheets (PlanExtras.swift); the shell can route them natively later.
struct PlanView: View {
    let component: PlanComponent
    @StateObject private var state: StateFlowObserver<PlanState>
    @Environment(\.defernoColors) private var colors

    /// Local "what's next" sheet (a decision helper) — no shared state, no shell ripple.
    @State private var showWhatNext = false
    /// Local "focus" sheet for a single chosen task — derived, presented, dismissed locally.
    /// `Task` isn't `Identifiable` on iOS, so we box it with its stable key for `.sheet(item:)`.
    @State private var focusItem: FocusItem?

    /// `Identifiable` wrapper around a `Task` for the focus `.sheet(item:)` (Task has no public id here).
    private struct FocusItem: Identifiable {
        let task: Task
        var id: String { task.stableKey }
    }

    init(component: PlanComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    /// "Nothing's overdue" or "{n} need attention" — gentle, never alarming. PlanState carries no
    /// deadline instants on iOS, so we count un-finished tasks as the calmest available proxy.
    ///
    /// Task rows only (#385): deciding that a recurring firing is unresolved needs the occurrence-fact
    /// table (#390) and the derivation in Half B, so a recurring row contributes zero rather than being
    /// guessed at in either direction.
    private func attentionLabel(_ rows: [PlanRow]) -> String {
        let open = rows.compactMap(\.task).filter { !$0.workingState.isTerminal }.count
        return open == 0 ? L.string("plan_all_caught_up") : L.plural("plan_still_open", open)
    }

    private var headerDate: String {
        let f = DateFormatter()
        f.dateFormat = L.string("plan_header_date_pattern")
        return f.string(from: Date())
    }

    var body: some View {
        let value = state.value
        let rows = value.rows
        // What's-next and Focus are Task verbs — "start this", "work on it until done". A recurring
        // commitment has no such verb, so both take the Task projection rather than every row.
        let taskProjection = rows.compactMap(\.task)
        // The ✦, from the **one** shared precedence rule (#375): `PlanSuggestion.kt` in `:feature:plan`,
        // which Compose and macOS call too — this view no longer keeps a Swift copy of the four arms to
        // drift from theirs. It is fed the same Task projection, which is what makes the ✦ Task-only
        // (#385): the banner's verb is "Start", and starting is what you do to a Task — a Habit is a
        // commitment you keep, and tapping through leads to Focus, which is Task-shaped end to end. A day
        // of nothing but recurring rows gets no ✦, which is honest rather than a suggestion nobody can
        // act on.
        let suggestion: Task? = PlanSuggestionKt.suggestedTask(tasks: taskProjection)

        Group {
            if value.isRefreshing && rows.isEmpty {
                LoadingStrip(label: L.string("plan_refreshing"))
            } else if rows.isEmpty {
                EmptyStateView(
                    title: L.string("plan_empty_title"),
                    message: L.string("plan_empty_body")
                )
            } else {
                ScrollView {
                    if value.isRefreshing {
                        LoadingStrip(label: L.string("plan_refreshing"))
                    }
                    LazyVStack(alignment: .leading, spacing: 0) {
                        header(count: rows.count)

                        if let suggestion {
                            suggestionBanner(task: suggestion)
                                .padding(.horizontal, 20)
                                .padding(.bottom, 20)
                        }

                        SectionLabel(L.string("plan_your_day_section"))
                            .padding(.horizontal, 20)
                            .padding(.vertical, 8)

                        // A `PlanRow` has no `stableKey` (that helper unwraps `Task.id`, an erased value
                        // class). `item.id` is already the raw UUID String the plan is ordered by, and it
                        // is unique across the day whatever kind the row is.
                        ForEach(Array(rows.enumerated()), id: \.element.item.id) { index, row in
                            // The `suggestion != nil` check is load-bearing, not defensive: a bare
                            // `row.task?.stableKey == suggestion?.stableKey` compares nil to nil on an
                            // all-recurring day — nothing was suggested, no row has a Task — and would
                            // highlight every row as the ✦.
                            let isSuggested = suggestion != nil && row.task?.stableKey == suggestion?.stableKey
                            dayRow(row: row, highlighted: isSuggested)
                            if !isSuggested && index < rows.count - 1 {
                                Divider()
                                    .background(colors.outlineVariant)
                                    .padding(.horizontal, 20)
                            }
                        }

                        DashedAddButton(title: L.string("plan_add_from_forest")) {
                            // ponytail: no "add from forest" intent on PlanComponent — surface the
                            // decision helper as the gentlest available "where do I start" affordance.
                            showWhatNext = true
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 16)

                        HStack {
                            TextLink(title: L.string("plan_see_everything"), trailingChevron: true) {
                                // No "see everything" intent on PlanComponent; the Tasks tab is the full
                                // forest. Left as a no-op until the shell routes it (noted in summary).
                            }
                            Spacer()
                            MonoMeta(attentionLabel(rows))
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                }
                .refreshable { component.onRefresh() }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.background)
        .sheet(isPresented: $showWhatNext) {
            WhatNextView(tasks: taskProjection) { picked in
                showWhatNext = false
                focusItem = FocusItem(task: picked)
            }
        }
        .sheet(item: $focusItem) { item in
            FocusView(task: item.task)
        }
    }

    // MARK: - Hero header

    @ViewBuilder
    private func header(count: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center) {
                HStack(spacing: 10) {
                    Brandmark()
                    Text(L.string("plan_today_title"))
                        .font(.title.weight(.semibold))
                        .foregroundStyle(colors.onSurface)
                        .accessibilityAddTraits(.isHeader)
                }
                Spacer()
                MonoMeta(headerDate)
            }
            Text(L.plural("plan_today_subtitle", count))
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    // MARK: - Suggestion banner

    @ViewBuilder
    private func suggestionBanner(task: Task) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                DefernoIcon.sparkle.image(size: 16).foregroundStyle(colors.primary)
                Eyebrow(L.string("plan_suggestion_eyebrow"))
            }
            Text(task.title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            StartPill {
                // Open the task (its detail) — the closest analogue to "Start" on the flat PlanState.
                component.onTaskClicked(id: task.id)
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(colors.primaryContainer, lineWidth: 1)
        )
    }

    // MARK: - Day row

    /// A single "Your day" row. The suggested one is a highlighted card with a ✦ before the title; the
    /// rest are flat (the list draws the dividers).
    ///
    /// **A row is one of four kinds (#385).** A Task row keeps everything it had — the leading `CheckDot`
    /// showing done-ness, the time subline, the open-on-tap. There is **no plan-completion intent** on
    /// `PlanComponent`, so tapping the dot does not toggle; it opens the task (where the working state can
    /// actually be changed). A Habit/Chore/Event row renders its title and a kind marker, and deliberately
    /// carries neither:
    ///
    /// - **No completion control.** A firing's done-state is a *reading* against today, not a stored fact
    ///   (ADR-0053), and the fact table it will be derived from does not exist yet (#390). An unchecked
    ///   dot would assert "not done" on no evidence, so a `KindDot` marks the row instead.
    /// - **No tap.** Still nowhere to go from *here* — though no longer for the original reason. A
    ///   recurring definition does have a detail surface since #383, but it opens off the **Tasks**
    ///   Destination's slot, which takes an `ItemRef`; the Plan stack's own open seam is
    ///   `PlanComponent.onTaskClicked(id: TaskId)`, and `TaskId` is exactly what a recurring id must never
    ///   be minted into (it applies nothing locally and 404s as a write the outbox reads as success).
    ///   Widening that seam is shared-shell work, not this View's. So neither the tap gesture nor the
    ///   "opens the tree" hint is attached — a row that cannot be opened must not announce that it can.
    @ViewBuilder
    private func dayRow(row: PlanRow, highlighted: Bool) -> some View {
        let item = row.item
        let task = row.task
        let done = task?.workingState == WorkingState.done
        // nil for a recurring row — there is nowhere to go, so no gesture is attached at all.
        let openTask: (() -> Void)? = task.map { t in { component.onTaskClicked(id: t.id) } }

        HStack(alignment: .center, spacing: 8) {
            if let task {
                CheckDot(checked: done) {
                    // No completion intent yet — opening the task is the honest action.
                    component.onTaskClicked(id: task.id)
                }
            } else {
                // Centred in the CheckDot's own footprint so the two row shapes share a title column — a
                // bare KindDot would leave a recurring row's title inset from every Task's, which reads
                // as a nesting level that isn't there.
                KindDot(color: kindColor(item.kind, colors))
                    .frame(width: Layout.checkDotSize, height: Layout.checkDotSize)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if highlighted {
                        DefernoIcon.sparkle.image(size: 14).foregroundStyle(colors.primary)
                    }
                    Text(item.title)
                        .font(.headline)
                        // Blocked mutes (but doesn't strike) like the tree row (#290/#292); a blocked
                        // item manually added to the plan is retained, just flagged.
                        .foregroundStyle((done || item.blocked) ? colors.inkMuted : colors.onSurface)
                        .strikethrough(done, color: colors.inkMuted)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if item.blocked {
                        TreeChip(text: L.string("common_blocked"), tone: .neutral)
                    }
                }
                // A Task's subline is its time label; a recurring row's is its kind, which is the fact
                // that makes it legible as something other than a Task. The cadence phrase ("every
                // Tuesday") wants the recurrence reading and lands with the rest of the row work in #383.
                MonoMeta(task.map { BridgeKt.taskTimeLabel(task: $0) } ?? kindDisplayLabel(item.kind))
            }
            Spacer(minLength: 8)
        }
        .frame(minHeight: Layout.rowMinHeight, alignment: .center)
        .padding(.horizontal, highlighted ? 12 : 20)
        .padding(.vertical, 12)
        .background(
            Group {
                if highlighted {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(colors.surfaceCard)
                }
            }
        )
        .padding(.horizontal, highlighted ? 20 : 0)
        .contentShape(Rectangle())
        .planRowTap(openTask)
        .accessibilityElement(children: .combine)
        // The blocked flag localizes like every other word VoiceOver reads — it used to be a literal
        // English ", blocked" that never translated (#393). Joined through `common_a11y_phrase_join`,
        // never a literal ", ": separator and order belong to the translator.
        .accessibilityLabel(rowA11yLabel(row))
        // Only a Task row can be opened, so only a Task row may say so. Attached to EVERY row before
        // #385, which on a non-tappable recurring row would have been a spoken lie.
        .planRowHint(task == nil ? nil : L.string("plan_row_opens_tree_hint"))
    }

    /// "Water the plants, blocked, In progress" for a Task; "Take a Walk, habit" for a recurring row —
    /// a recurring definition has no `workingState`, so it speaks its kind where a Task speaks its state
    /// (mirrors the Compose row, which folds the kind into the title's label on the same catalog key).
    private func rowA11yLabel(_ row: PlanRow) -> String {
        let trailing = row.task.map { $0.workingState.label } ?? kindA11yLabel(row.item.kind)
        let base = row.item.blocked
            ? L.format("common_a11y_phrase_join", row.item.title, L.string("common_blocked"))
            : row.item.title
        return L.format("common_a11y_phrase_join", base, trailing)
    }
}

private extension View {
    /// Attaches the row's open-on-tap only when there is somewhere to go. `nil` leaves the row inert
    /// rather than wiring a gesture that swallows the touch and does nothing (#385/#383).
    @ViewBuilder
    func planRowTap(_ action: (() -> Void)?) -> some View {
        if let action {
            onTapGesture(perform: action)
        } else {
            self
        }
    }

    /// Attaches the VoiceOver hint only when it is true of the row. An empty hint is still a hint element;
    /// the absence of the modifier is what makes a recurring row silent about opening.
    @ViewBuilder
    func planRowHint(_ hint: String?) -> some View {
        if let hint {
            accessibilityHint(hint)
        } else {
            self
        }
    }
}
