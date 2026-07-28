import Deferno
import SwiftUI

/// The Plan Destination host (#51) — a **tier-3 drill-down** (`PlanChild`: Dashboard ↔ Detail(task)).
/// A Plan tap pushes the Task's detail onto the Plan stack; a subtask drill pushes deeper. The single
/// adaptive shell bar (`MainShellView`) titles each surface and drives ← back, so the detail is hosted
/// header-less. Renders the active child inline, mirroring `SettingsView`'s tier-3 stack — no shell
/// overlay any more (the detail used to be a sheet).
struct PlanHostView: View {
    let plan: MainShellComponentDestinationChildPlan
    /// Routes the dashboard's "See everything ›" link. `PlanComponent` has no such intent — the Tasks
    /// Destination *is* the whole forest — so only the shell can honour it; it hands this down (#368 G16).
    /// Nil keeps the link an honest no-op (the iOS behaviour) rather than a compile break.
    let onSeeEverything: (() -> Void)?
    @StateObject private var stack: StateFlowObserver<MainShellComponentPlanChild>

    init(plan: MainShellComponentDestinationChildPlan, onSeeEverything: (() -> Void)? = nil) {
        self.plan = plan
        self.onSeeEverything = onSeeEverything
        _stack = StateObject(wrappedValue: StateFlowObserver(plan.activeChild))
    }

    var body: some View {
        let child = stack.value
        if let dashboard = ShellBridgeKt.planChildDashboard(child: child) {
            PlanView(component: dashboard, onSeeEverything: onSeeEverything)
        } else if let detail = ShellBridgeKt.planChildDetail(child: child) {
            TaskDetailView(component: detail, showsHeader: false).id(BridgeKt.detailKey(component: detail))
        }
    }
}

/// The daily Plan pane (#51) restyled to the "See the trees" direction — the app's calm home
/// (design-principles.md: "open into today's Plan, not the whole backlog"). A thin renderer of
/// `PlanComponent`: observes today's ordered Tasks and forwards taps (open the Task), holding no logic
/// of its own beyond what it derives from `tasks`.
///
/// The macOS twin of the iOS `PlanView` (#368 G16 — this pane used to be a bare `List` of `TaskRow`,
/// which is *correct* but not the home screen the other two platforms open into). Mirrors the Android
/// `PlanScreen` restyle: a hero header (Brandmark + "Today" + date + a gentle count subtitle), an
/// "IF YOU'RE NOT SURE, START HERE" suggestion banner, a "YOUR DAY" list of `CheckDot` rows, an
/// "Add from the forest" dashed footer, and a "See everything ›" link with an attention count.
///
/// What's-next / Focus are **derived client-side from `tasks`** (PlanState carries only `{ tasks,
/// isRefreshing }`) — exactly as `PlanScreen.kt` derives them — and presented as sheets (PlanExtras.swift).
///
/// Deliberate macOS divergences from the iOS twin, all called out at their sites: no `.refreshable`
/// (there is no pull gesture on a Mac; ⌘R already routes refresh through `refreshActiveDestination`),
/// a pointer hover cue on the day rows, sized sheets, and a live "See everything ›".
struct PlanView: View {
    let component: PlanComponent
    /// See `PlanHostView.onSeeEverything`.
    let onSeeEverything: (() -> Void)?
    @StateObject private var state: StateFlowObserver<PlanState>
    @Environment(\.defernoColors) private var colors

    /// Local "what's next" sheet (a decision helper) — no shared state, no shell ripple.
    @State private var showWhatNext = false
    /// Local "focus" sheet for a single chosen task — derived, presented, dismissed locally.
    /// `Task` isn't `Identifiable` (Bridge/Identity.swift conforms the shell enums, not the model types,
    /// and nothing else may re-declare a conformance), so we box it with its stable key for `.sheet(item:)`.
    @State private var focusItem: FocusItem?
    /// The row the pointer is over — the desktop cue the touch-first iOS twin has no need for.
    @State private var hoveredKey: String?

    /// `Identifiable` wrapper around a `Task` for the focus `.sheet(item:)`.
    private struct FocusItem: Identifiable {
        let task: Task
        var id: String { task.stableKey }
    }

    init(component: PlanComponent, onSeeEverything: (() -> Void)? = nil) {
        self.component = component
        self.onSeeEverything = onSeeEverything
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    /// The task we gently suggest starting with: the first pinned one, else the first non-terminal,
    /// else simply the first (mirrors `PlanScreen.suggested()`).
    private func suggested(_ tasks: [Task]) -> Task? {
        tasks.first(where: { $0.pinned })
            ?? tasks.first(where: { !$0.workingState.isTerminal })
            ?? tasks.first
    }

    /// "Nothing's overdue" or "{n} need attention" — gentle, never alarming. PlanState carries no
    /// deadline instants here, so we count un-finished tasks as the calmest available proxy.
    private func attentionLabel(_ tasks: [Task]) -> String {
        let open = tasks.filter { !$0.workingState.isTerminal }.count
        return open == 0 ? L.string("plan_all_caught_up") : L.plural("plan_still_open", open)
    }

    private var headerDate: String {
        let f = DateFormatter()
        f.dateFormat = L.string("plan_header_date_pattern")
        return f.string(from: Date())
    }

    var body: some View {
        let value = state.value
        let tasks = value.tasks
        let suggestion = suggested(tasks)

        Group {
            if value.isRefreshing && tasks.isEmpty {
                LoadingStrip(label: L.string("plan_refreshing"))
            } else if tasks.isEmpty {
                EmptyStateView(
                    title: L.string("plan_empty_title"),
                    message: L.string("plan_empty_body")
                )
            } else {
                ScrollView {
                    // No `.refreshable`: macOS has no pull-to-refresh gesture, so it would imply a control
                    // that doesn't exist. ⌘R already refreshes the foreground Destination through
                    // `refreshActiveDestination`, which walks the Plan's tier-3 stack to this dashboard.
                    // The in-body strip stays so that refresh is still *visible*.
                    if value.isRefreshing {
                        LoadingStrip(label: L.string("plan_refreshing"))
                    }
                    LazyVStack(alignment: .leading, spacing: 0) {
                        header(count: tasks.count)

                        if let suggestion {
                            suggestionBanner(task: suggestion)
                                .padding(.horizontal, 20)
                                .padding(.bottom, 20)
                        }

                        SectionLabel(L.string("plan_your_day_section"))
                            .padding(.horizontal, 20)
                            .padding(.vertical, 8)

                        ForEach(Array(tasks.enumerated()), id: \.element.stableKey) { index, task in
                            dayRow(task: task, highlighted: task.stableKey == suggestion?.stableKey)
                            if task.stableKey != suggestion?.stableKey && index < tasks.count - 1 {
                                Divider()
                                    .background(colors.outlineVariant)
                                    .padding(.horizontal, 20)
                            }
                        }

                        DashedAddButton(title: L.string("plan_add_from_forest")) {
                            // No "add from forest" intent on PlanComponent — surface the decision helper
                            // as the gentlest available "where do I start" affordance.
                            showWhatNext = true
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 16)

                        HStack {
                            TextLink(title: L.string("plan_see_everything"), trailingChevron: true) {
                                onSeeEverything?()
                            }
                            Spacer()
                            MonoMeta(attentionLabel(tasks))
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.background)
        // Both surfaces are sheets rather than auxiliary windows. WhatNext is a five-second modal decision
        // that hands off to Focus and must then disappear — a sheet is exactly right. Focus arguably wants
        // ADR-0033's auxiliary-scene treatment ("one task, everything else put away" is a sustained working
        // mode, not a modal), but a scene cannot inherit `\.defernoColors` across the boundary and would
        // have to mirror the theme itself like `TaskDetailWindowView` does, and its `WindowGroup(for:)`
        // payload would have to be a Codable id re-resolved against a root this pane doesn't hold. That is
        // a bigger surface than this port should take on; sheets first, a Focus scene as a follow-up.
        //
        // macOS sheets size to their content (no device-height default like iOS), so each presented view
        // carries an explicit floor — same posture as the DraftExtractor sheet in DefernoApp.
        .sheet(isPresented: $showWhatNext) {
            WhatNextView(tasks: tasks) { picked in
                showWhatNext = false
                focusItem = FocusItem(task: picked)
            }
            .frame(minWidth: 420, minHeight: 520)
        }
        .sheet(item: $focusItem) { item in
            FocusView(task: item.task)
                .frame(minWidth: 420, minHeight: 520)
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
    /// The leading `CheckDot` shows the task's done-ness (`workingState == WorkingState.done`). There is
    /// **no plan-completion intent** on `PlanComponent`, so clicking the dot does not toggle — it opens
    /// the task (where the working state can actually be changed). Mirrors the Android note that
    /// completion needs a future `onToggleDone` intent.
    ///
    /// This row *replaces* `TaskRow` on the Plan, so it carries `TaskRow`'s two blocked behaviours itself
    /// (#368): the muted-but-unstruck title and the "…, blocked, …" spoken label. Dropping them here
    /// because "macOS already has them in TaskRow" would silently regress this surface.
    @ViewBuilder
    private func dayRow(task: Task, highlighted: Bool) -> some View {
        let done = task.workingState == WorkingState.done
        let hovering = hoveredKey == task.stableKey

        HStack(alignment: .center, spacing: 8) {
            CheckDot(checked: done) {
                // No completion intent yet — opening the task is the honest action.
                component.onTaskClicked(id: task.id)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if highlighted {
                        DefernoIcon.sparkle.image(size: 14).foregroundStyle(colors.primary)
                    }
                    Text(task.title)
                        .font(.headline)
                        // Blocked mutes (but doesn't strike) like the tree row (#290/#292); a blocked
                        // item manually added to the plan is retained, just flagged.
                        .foregroundStyle((done || task.blocked) ? colors.inkMuted : colors.onSurface)
                        .strikethrough(done, color: colors.inkMuted)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if task.blocked {
                        // macOS has no `TreeChip` on purpose (Atoms.swift's closing note): `DependencyBadge`
                        // is the one mono badge, and its `semanticLabel` is required so VoiceOver never
                        // reads the uppercased glyphs.
                        DependencyBadge(
                            text: L.string("common_blocked"),
                            tone: .neutral,
                            semanticLabel: L.string("common_blocked")
                        )
                    }
                }
                MonoMeta(BridgeKt.taskTimeLabel(task: task))
            }
            Spacer(minLength: 8)
        }
        .frame(minHeight: Layout.rowMinHeight, alignment: .center)
        .padding(.horizontal, highlighted ? 12 : 20)
        .padding(.vertical, 12)
        .background { rowBackground(highlighted: highlighted, hovering: hovering) }
        .padding(.horizontal, highlighted ? 20 : 0)
        .contentShape(Rectangle())
        .onHover { inside in
            // Compare against the live state, not the captured `hovering`: rows are recycled by the
            // LazyVStack, so an exit callback can arrive after another row has claimed the pointer.
            if inside { hoveredKey = task.stableKey } else if hoveredKey == task.stableKey { hoveredKey = nil }
        }
        .onTapGesture { component.onTaskClicked(id: task.id) }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(task.blocked ? "\(task.title), blocked, \(task.workingState.label)" : "\(task.title), \(task.workingState.label)")
        .accessibilityHint(L.string("plan_row_opens_tree_hint"))
    }

    /// The day row's fill: the suggested row is a card, a hovered row gets the same `surfaceVariant` lift
    /// the other flat click surfaces use (`DashedAddButton`, the Trail rows). The suggested card is already
    /// inset by the outer padding; a plain row is edge-to-edge, so its hover fill is inset here rather than
    /// by moving the text.
    @ViewBuilder
    private func rowBackground(highlighted: Bool, hovering: Bool) -> some View {
        if highlighted || hovering {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(highlighted ? colors.surfaceCard : colors.surfaceVariant)
                .padding(.horizontal, highlighted ? 0 : 12)
        }
    }
}
