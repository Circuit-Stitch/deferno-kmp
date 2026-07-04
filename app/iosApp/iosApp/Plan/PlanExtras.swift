import Deferno
import SwiftUI

// The Plan's two **local** decision surfaces (iOS twins of `PlanScreen.kt`'s WhatsNext + Focus
// sub-screens). Both are derived purely from the already-loaded `[Task]` — PlanState carries no
// what's-next/focus state, so there is no Decompose, no shell ripple: PlanView presents them as sheets
// and the shell can route them natively later. Calm, parchment, mono-eyebrow voice.

// MARK: - What's next? — a decision helper

/// A calm "where do I start" helper. It shows **one** suggested task at a time; "This one" picks it
/// (`onPick`), "Something else" cycles to the next idea without judgement. Derives its candidates from
/// `tasks` (pinned + still-open first), so it never asks the user to scan the whole list.
struct WhatNextView: View {
    let tasks: [Task]
    let onPick: (Task) -> Void

    @Environment(\.defernoColors) private var colors
    @Environment(\.dismiss) private var dismiss
    @State private var index = 0

    /// The ordered ideas: pinned first, then still-open, then the rest — de-duplicated by identity.
    private var candidates: [Task] {
        var seen = Set<String>()
        var ordered: [Task] = []
        func add(_ list: [Task]) {
            for t in list where seen.insert(t.stableKey).inserted { ordered.append(t) }
        }
        add(tasks.filter { $0.pinned })
        add(tasks.filter { !$0.workingState.isTerminal })
        add(tasks)
        return ordered
    }

    private var current: Task? {
        let list = candidates
        guard !list.isEmpty else { return nil }
        return list[index % list.count]
    }

    /// The derived "why" line for the current idea (mirrors `PlanScreen.whyLine`).
    private func whyLine(_ task: Task) -> String {
        if task.pinned { return L.string("plan_why_pinned") }
        if !task.workingState.isTerminal { return L.string("plan_why_quick_win") }
        return L.string("plan_picker_already_done")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Eyebrow(L.string("plan_whats_next_title"))
                Spacer()
                TextLink(title: L.string("common_close")) { dismiss() }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)

            Text(L.string("plan_whats_next_title"))
                .font(.title.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)
                .padding(.top, 8)

            Text(L.string("plan_picker_subtitle"))
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
                .padding(.horizontal, 20)
                .padding(.top, 6)

            Spacer(minLength: 24)

            if let task = current {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        DefernoIcon.sparkle.image(size: 16).foregroundStyle(colors.primary)
                        SectionLabel(L.string("plan_picker_how_about"))
                    }
                    Text(task.title)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(colors.onSurface)
                        .lineLimit(3)
                        .multilineTextAlignment(.leading)
                    Text(whyLine(task))
                        .font(.subheadline)
                        .foregroundStyle(colors.onSurfaceVariant)
                    MonoMeta(BridgeKt.taskTimeLabel(task: task))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
                .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(colors.primaryContainer, lineWidth: 1)
                )
                .padding(.horizontal, 20)
                .id(task.stableKey) // gentle change-of-card when cycling

                Spacer(minLength: 24)

                VStack(spacing: 10) {
                    PrimaryActionButton(title: L.string("plan_picker_this_one"), icon: .check) {
                        onPick(task)
                    }
                    TonalActionButton(title: L.string("plan_picker_something_else"), icon: .refresh) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            index = (index + 1) % max(1, candidates.count)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 20)
            } else {
                EmptyStateView(
                    title: L.string("plan_picker_empty_title"),
                    message: L.string("plan_picker_empty_body")
                )
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(colors.background)
    }
}

// MARK: - Focus — a single-task surface

/// One task, everything else put away. A gentle breathing ring around a clock honours reduced-motion
/// (`@Environment(\.accessibilityReduceMotion)` → static ring). Self-contained: dismiss returns to the
/// Plan. No completion intent on the flat PlanState, so "Done" simply closes the surface (the real
/// working-state change happens in the task detail).
struct FocusView: View {
    let task: Task

    @Environment(\.defernoColors) private var colors
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                TextLink(title: L.string("plan_exit_focus")) { dismiss() }
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 16)

            Spacer()

            BreathingRing(reduceMotion: reduceMotion)

            Spacer().frame(height: 28)

            Text(task.title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)

            Text(L.string("plan_focus_subtitle"))
                .font(.subheadline)
                .foregroundStyle(colors.inkMuted)
                .multilineTextAlignment(.center)
                .padding(.top, 12)
                .padding(.horizontal, 24)

            Spacer()

            VStack(spacing: 8) {
                PrimaryActionButton(title: L.string("plan_focus_done"), icon: .check) { dismiss() }
                TextLink(title: L.string("plan_focus_pause")) { dismiss() }
                    .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.background)
    }
}

/// The breathing focus ring around a clock glyph. Static when `reduceMotion` (design: honour
/// reduced-motion); otherwise a slow 2.6s scale + alpha pulse.
private struct BreathingRing: View {
    let reduceMotion: Bool
    @Environment(\.defernoColors) private var colors
    @State private var animating = false

    private var scale: CGFloat { reduceMotion ? 1.0 : (animating ? 1.06 : 0.94) }
    private var ringOpacity: Double { reduceMotion ? 0.5 : (animating ? 0.65 : 0.30) }

    var body: some View {
        ZStack {
            // Outer breathing ring.
            Circle()
                .strokeBorder(colors.primary, lineWidth: 3)
                .frame(width: 180, height: 180)
                .scaleEffect(scale)
                .opacity(ringOpacity)
            // Inner static ring + clock glyph.
            Circle()
                .strokeBorder(colors.primary.opacity(0.5), lineWidth: 2)
                .frame(width: 120, height: 120)
            DefernoIcon.clock.image(size: 34).foregroundStyle(colors.primary)
        }
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 2.6).repeatForever(autoreverses: true)) {
                animating = true
            }
        }
    }
}
