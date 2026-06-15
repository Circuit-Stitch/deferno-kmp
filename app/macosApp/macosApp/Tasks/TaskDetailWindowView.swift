import Combine
import Deferno
import SwiftUI

/// A **detached, navigable per-task detail window** (#196, ADR-0030). Owns a `TaskDetailWindowRoot`
/// (the per-window Decompose stack over the **live** account session — same SQLite driver as the main
/// shell, so edits sync across windows for free) and renders its foreground `TaskDetailComponent` as a
/// `TaskDetailView`. Drilling a subtask pushes the child's detail; the detail's own header Back pops;
/// at the root (depth 1) the Back control is hidden — the window's own chrome closes it.
///
/// The window closes itself when there is no active session at open (signed out → nothing to show) and
/// on sign-out / account switch while open (account isolation — never leave another account's task up).
struct TaskDetailWindowView: View {
    @StateObject private var model: TaskDetailWindowModel
    @StateObject private var rootStack: RootStackObserver
    @Environment(\.dismiss) private var dismiss
    @State private var boundChild: RootComponentChild?

    init(host: DefernoRoot, rawId: String) {
        _model = StateObject(wrappedValue: TaskDetailWindowModel(host: host, rawId: rawId))
        _rootStack = StateObject(wrappedValue: RootStackObserver(ShellBridgeKt.rootStackBridge(component: host.root)))
    }

    var body: some View {
        Group {
            if let active = model.active {
                // The root entry has nothing to pop to (the OS chrome closes the window), so hide its
                // Back; a drilled entry keeps the header Back, which pops via the detail's Closed output.
                TaskDetailView(component: active, hidesBackControl: !model.canGoBack)
                    .id(BridgeKt.detailKey(component: active))
            } else {
                // No active session at open (signed out) or an unusable id — nothing to show; close.
                Color.clear.onAppear { dismiss() }
            }
        }
        .frame(minWidth: 360, minHeight: 360)
        // Title the window with the task's ref (e.g. "u-e4h2qk-1") so multiple detail windows are
        // distinguishable in the title bar / Window menu / Mission Control (#196).
        .navigationTitle(model.title)
        .onReceive(rootStack.$active) { child in
            // Account isolation (ADR-0030): the root swaps its active child on sign-out (→ Auth) and on
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

/// Builds and OWNS one detached window's `TaskDetailWindowRoot` for the lifetime of its SwiftUI scene:
/// constructed at init (over the active session — `nil` when signed out), torn down in `deinit`
/// (`destroy()` → `lifecycle.destroy()`, so the window's component tree leaks nothing across open/close).
/// Republishes the stack's foreground detail + whether a level can be popped, on the Kotlin main thread.
final class TaskDetailWindowModel: ObservableObject {
    @Published private(set) var active: TaskDetailComponent?
    @Published private(set) var canGoBack = false
    /// The foreground entry's ref (e.g. "u-e4h2qk-1"), used as the window title. Falls back to the task
    /// title when the task has no ref (so the window is never blank), and re-points as you drill.
    @Published private(set) var title = ""

    private let windowRoot: TaskDetailWindowRoot?
    private var subscription: Deferno.Subscription?
    private var titleSubscription: Deferno.Subscription?

    init(host: DefernoRoot, rawId: String) {
        let root = TaskDetailWindowRootKt.openTaskDetailWindow(root: host.root, idValue: rawId)
        windowRoot = root
        if let root {
            active = root.stack.active
            canGoBack = root.stack.canGoBack
            bindTitle(to: root.stack.active)
            subscription = root.stack.subscribe(onEach: { [weak self] component in
                self?.active = component
                self?.canGoBack = root.stack.canGoBack
                self?.bindTitle(to: component)
            })
        }
    }

    // Track the foreground detail's ref for the window title; re-subscribes on each push/pop.
    private func bindTitle(to component: TaskDetailComponent) {
        titleSubscription?.cancel()
        let bridge = BridgeKt.taskDetailStateBridge(component: component)
        title = Self.titleFor(bridge.value)
        titleSubscription = bridge.subscribe(onEach: { [weak self] state in
            self?.title = Self.titleFor(state)
        })
    }

    private static func titleFor(_ state: TaskDetailState) -> String {
        state.task?.ref ?? state.task?.title ?? ""
    }

    deinit {
        subscription?.cancel()
        titleSubscription?.cancel()
        windowRoot?.destroy()
    }
}
