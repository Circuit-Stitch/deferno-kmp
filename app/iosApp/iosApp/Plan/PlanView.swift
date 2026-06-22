import Deferno
import SwiftUI

/// The Plan Destination host (#51) — a **tier-3 drill-down** (`PlanChild`: Dashboard ↔ Detail(task)).
/// A Plan tap pushes the Task's detail onto the Plan stack; a subtask drill pushes deeper. The whole
/// stack renders inline inside the shell chrome (the drawer stays live) — no shell overlay any more (the
/// detail used to be a `.sheet`). The single adaptive shell bar (`MainShellView`) titles each surface and
/// drives back, so the detail is hosted header-less; mirrors macOS's `PlanHostView`.
struct PlanHostView: View {
    let plan: MainShellComponentDestinationChildPlan
    @StateObject private var stack: PlanStackObserver

    init(plan: MainShellComponentDestinationChildPlan) {
        self.plan = plan
        _stack = StateObject(wrappedValue: PlanStackObserver(ShellBridgeKt.planStackBridge(plan: plan)))
    }

    var body: some View {
        let child = stack.active
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
/// [PlanComponent]: observes today's ordered Tasks and forwards taps (open the Task) / refresh.
///
/// Mirrors the Android `PlanScreen` restyle: a hero header (Brandmark + "Today" + date + a gentle count
/// subtitle), a "IF YOU'RE NOT SURE, START HERE" suggestion banner, a "YOUR DAY" list of `CheckDot` rows,
/// an "Add from the forest" dashed footer, and a "See everything ›" link with an attention count.
///
/// What's-next / Focus are **derived client-side from `tasks`** (PlanState carries only `{ tasks,
/// isRefreshing }`) — exactly as `PlanScreen.kt` derives them. The suggestion card presents the
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
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.planStateBridge(component: component)))
    }

    /// The task we gently suggest starting with: the first pinned one, else the first non-terminal,
    /// else simply the first (mirrors `PlanScreen.suggested()`).
    private func suggested(_ tasks: [Task]) -> Task? {
        tasks.first(where: { $0.pinned })
            ?? tasks.first(where: { !$0.workingState.isTerminal })
            ?? tasks.first
    }

    /// "Nothing's overdue" or "{n} need attention" — gentle, never alarming. PlanState carries no
    /// deadline instants on iOS, so we count un-finished tasks as the calmest available proxy.
    private func attentionLabel(_ tasks: [Task]) -> String {
        let open = tasks.filter { !$0.workingState.isTerminal }.count
        return open == 0 ? "All caught up" : "\(open) still open"
    }

    private var headerDate: String {
        let f = DateFormatter()
        f.dateFormat = "EEE, MMM d"
        return f.string(from: Date())
    }

    var body: some View {
        let value = state.value
        let tasks = value.tasks
        let suggestion = suggested(tasks)

        Group {
            if value.isRefreshing && tasks.isEmpty {
                LoadingStrip(label: "Refreshing your plan…")
            } else if tasks.isEmpty {
                EmptyStateView(
                    title: "Your plan is clear",
                    message: "Nothing scheduled for today. Add something when you're ready — no pressure."
                )
            } else {
                ScrollView {
                    if value.isRefreshing {
                        LoadingStrip(label: "Refreshing your plan…")
                    }
                    LazyVStack(alignment: .leading, spacing: 0) {
                        header(count: tasks.count)

                        if let suggestion {
                            suggestionBanner(task: suggestion)
                                .padding(.horizontal, 20)
                                .padding(.bottom, 20)
                        }

                        SectionLabel("Your day")
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

                        DashedAddButton(title: "Add from the forest") {
                            // ponytail: no "add from forest" intent on PlanComponent — surface the
                            // decision helper as the gentlest available "where do I start" affordance.
                            showWhatNext = true
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 16)

                        HStack {
                            TextLink(title: "See everything", trailingChevron: true) {
                                // No "see everything" intent on PlanComponent; the Tasks tab is the full
                                // forest. Left as a no-op until the shell routes it (noted in summary).
                            }
                            Spacer()
                            MonoMeta(attentionLabel(tasks))
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
            WhatNextView(tasks: tasks) { picked in
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
                    Text("Today")
                        .font(.title.weight(.semibold))
                        .foregroundStyle(colors.onSurface)
                        .accessibilityAddTraits(.isHeader)
                }
                Spacer()
                MonoMeta(headerDate)
            }
            Text("\(count) trees you picked. Start wherever feels right.")
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
                Eyebrow("If you're not sure, start here")
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
    /// The leading `CheckDot` shows the task's done-ness (`workingState === WorkingState.done`). There is
    /// **no plan-completion intent** on `PlanComponent`, so tapping the dot does not toggle — it opens the
    /// task (where the working state can actually be changed). Mirrors the Android note that completion
    /// needs a future `onToggleDone` intent.
    @ViewBuilder
    private func dayRow(task: Task, highlighted: Bool) -> some View {
        let done = task.workingState === WorkingState.done

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
                        .foregroundStyle(done ? colors.inkMuted : colors.onSurface)
                        .strikethrough(done, color: colors.inkMuted)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                MonoMeta(BridgeKt.taskTimeLabel(task: task))
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
        .onTapGesture { component.onTaskClicked(id: task.id) }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(task.title), \(task.workingState.label)")
        .accessibilityHint("Opens this tree")
    }
}
