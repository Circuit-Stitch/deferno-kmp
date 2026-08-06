import Combine
import Deferno
import SwiftUI

/// A **detached, navigable per-item detail window** (#196, ADR-0033). Owns an `ItemDetailWindowRoot` (the
/// per-window Decompose tree over the **live** account session — same SQLite driver as the main shell, so
/// edits sync across windows for free) and renders whichever detail its scene payload named.
///
/// **Either arm, since #383.** A Task opens the navigable `TaskDetailView` stack: drilling a subtask pushes
/// the child's detail, the detail's own header Back pops, and at the root (depth 1) the Back control is
/// hidden — the window's own chrome closes it. A Habit/Chore/Event opens the read-only
/// `DefinitionDetailView`, which has no drill and so never shows Back at all. The window used to be
/// Task-only *by gate* (`ItemRowContainer.openDetailWindow`'s `guard isTask`), and removing that gate is
/// only safe because the payload now carries the kind — see `openItemDetailWindow`.
///
/// The window closes itself when there is no active session at open (signed out → nothing to show) and
/// on sign-out / account switch while open (account isolation — never leave another account's item up).
struct ItemDetailWindowView: View {
    @StateObject private var model: ItemDetailWindowModel
    @StateObject private var rootStack: StateFlowObserver<RootComponentChild>
    /// **Every scene themes itself** (#368). SwiftUI environment values do NOT cross a scene boundary:
    /// `RootView` applies `.defernoTheme` *inside* the `main` `Window` scene, and this `task-detail`
    /// `WindowGroup` is a separate scene, so none of that reaches here. Without this observer the window
    /// would render off the `\.defernoColors` `EnvironmentKey` default (`DefernoColors.defernoLight`,
    /// DefernoTheme.swift) with no `.tint` and no `preferredColorScheme` — a light-amber window with dark
    /// native chrome for any Mono / Dark / Auto-dark user, in a `TaskDetailView` that reads the palette in
    /// ~17 places. It was latent only because the scene was unreachable; restoring the Open-in-New-Window
    /// trigger (#196, ADR-0033) is what ships it. Same `root.themeSettings` mirror `RootView` drives, so
    /// both scenes re-theme live and together.
    @StateObject private var theme: StateFlowObserver<UserSettings>
    @Environment(\.dismiss) private var dismiss
    @State private var boundChild: RootComponentChild?

    init(host: DefernoRoot, token: String) {
        _model = StateObject(wrappedValue: ItemDetailWindowModel(host: host, token: token))
        _rootStack = StateObject(wrappedValue: StateFlowObserver(host.root.activeChild))
        _theme = StateObject(wrappedValue: StateFlowObserver(host.root.themeSettings))
    }

    var body: some View {
        Group {
            if let definition = model.definition {
                // A read-only definition detail has nothing to pop and no window-internal navigation, so
                // its Back is always hidden — the OS chrome is the only way out, as at a Task stack's root.
                DefinitionDetailView(component: definition, hidesBackControl: true)
            } else if let active = model.active {
                // The root entry has nothing to pop to (the OS chrome closes the window), so hide its
                // Back; a drilled entry keeps the header Back, which pops via the detail's Closed output.
                // `hostsOverlays: false` — this scene has no shell overlay slot, and the window's stack
                // drops `Output.BreakdownRequested` (TaskDetailStackComponent), so the "Break this down"
                // item is suppressed here rather than shipped dead (#368 G10).
                TaskDetailView(component: active, hidesBackControl: !model.canGoBack, hostsOverlays: false)
                    .id(BridgeKt.detailKey(component: active))
            } else {
                // No active session at open (signed out) or an unusable payload — nothing to show; close.
                Color.clear.onAppear { dismiss() }
            }
        }
        .defernoTheme(theme.value)
        .frame(minWidth: 360, minHeight: 360)
        // Title the window with the item's ref (e.g. "u-e4h2qk-1") so multiple detail windows are
        // distinguishable in the title bar / Window menu / Mission Control (#196).
        .navigationTitle(model.title)
        .onReceive(rootStack.$value) { child in
            // Account isolation (ADR-0033): the root swaps its active child on sign-out (→ Auth) and on
            // account switch (→ a re-keyed Main for the new account). Either way this window's captured
            // session is no longer active, so close it. The first value binds; only a *change* dismisses.
            if let bound = boundChild {
                if (bound as AnyObject) !== (child as AnyObject) { dismiss() }
            } else {
                boundChild = child
            }
        }
    }
}

/// Builds and OWNS one detached window's `ItemDetailWindowRoot` for the lifetime of its SwiftUI scene:
/// constructed at init (over the active session — `nil` when signed out), torn down in `deinit`
/// (`destroy()` → `lifecycle.destroy()`, so the window's component tree leaks nothing across open/close).
/// Republishes what its arm needs, on the main actor (the components' `StateFlow` mirrors are bridged by
/// SKIE, whose iterators run off the main thread).
///
/// Exactly one arm is live: [definition] is a stored constant (a read-only detail has no stack to
/// observe), while the Task arm republishes the stack's foreground detail + whether a level can be popped.
final class ItemDetailWindowModel: ObservableObject {
    @Published private(set) var active: TaskDetailComponent?
    @Published private(set) var canGoBack = false
    /// The foreground entry's ref (e.g. "u-e4h2qk-1"), used as the window title. Falls back to the item
    /// title when it has no ref (so the window is never blank), and re-points as you drill.
    @Published private(set) var title = ""

    /// The recurring-definition arm, or nil for a Task window (or no session at all).
    let definition: DefinitionDetailComponent?

    private let windowRoot: ItemDetailWindowRoot?
    // `_Concurrency.Task`: `Deferno.Task` (the Kotlin model) shadows Swift's concurrency `Task` here.
    private var activeTask: _Concurrency.Task<Void, Never>?
    private var backTask: _Concurrency.Task<Void, Never>?
    private var titleTask: _Concurrency.Task<Void, Never>?

    init(host: DefernoRoot, token: String) {
        let root = ItemDetailWindowRootKt.openItemDetailWindow(host: host, token: token)
        windowRoot = root
        definition = root?.definitionDetail
        if let definition = root?.definitionDetail {
            bindDefinitionTitle(to: definition)
        } else if let stack = root?.taskStack {
            active = stack.activeDetail.value
            canGoBack = stack.canGoBack.value.boolValue
            bindTitle(to: stack.activeDetail.value)
            activeTask = _Concurrency.Task { @MainActor [weak self] in
                for await component in stack.activeDetail {
                    guard !_Concurrency.Task.isCancelled, let self else { return }
                    self.active = component
                    self.bindTitle(to: component)
                }
            }
            backTask = _Concurrency.Task { @MainActor [weak self] in
                for await value in stack.canGoBack {
                    guard !_Concurrency.Task.isCancelled, let self else { return }
                    self.canGoBack = value.boolValue
                }
            }
        }
    }

    // Track the foreground detail's ref for the window title; re-subscribes on each push/pop.
    private func bindTitle(to component: TaskDetailComponent) {
        titleTask?.cancel()
        let flow = component.state
        title = Self.titleFor(flow.value)
        titleTask = _Concurrency.Task { @MainActor [weak self] in
            for await state in flow {
                guard !_Concurrency.Task.isCancelled, let self else { return }
                self.title = Self.titleFor(state)
            }
        }
    }

    /// The definition arm's title. It never re-points (there is no drill), but it must still follow the
    /// state: a window opened from a cached tree row has no `ref` until the detail read lands.
    private func bindDefinitionTitle(to component: DefinitionDetailComponent) {
        let flow = component.state
        title = Self.titleFor(flow.value)
        titleTask = _Concurrency.Task { @MainActor [weak self] in
            for await state in flow {
                guard !_Concurrency.Task.isCancelled, let self else { return }
                self.title = Self.titleFor(state)
            }
        }
    }

    private static func titleFor(_ state: TaskDetailState) -> String {
        state.task?.ref ?? state.task?.title ?? ""
    }

    private static func titleFor(_ state: DefinitionDetailState) -> String {
        state.definition?.ref ?? state.definition?.title ?? ""
    }

    deinit {
        activeTask?.cancel()
        backTask?.cancel()
        titleTask?.cancel()
        windowRoot?.destroy()
    }
}
