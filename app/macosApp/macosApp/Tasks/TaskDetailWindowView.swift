import Combine
import Deferno
import SwiftUI

/// A **detached, navigable per-task detail window** (#196, ADR-0033). Owns a `TaskDetailWindowRoot`
/// (the per-window Decompose stack over the **live** account session — same SQLite driver as the main
/// shell, so edits sync across windows for free) and renders its foreground `TaskDetailComponent` as a
/// `TaskDetailView`. Drilling a subtask pushes the child's detail; the detail's own header Back pops;
/// at the root (depth 1) the Back control is hidden — the window's own chrome closes it.
///
/// The window closes itself when there is no active session at open (signed out → nothing to show) and
/// on sign-out / account switch while open (account isolation — never leave another account's task up).
struct TaskDetailWindowView: View {
    @StateObject private var model: TaskDetailWindowModel
    @StateObject private var rootStack: StateFlowObserver<RootComponentChild>
    @Environment(\.dismiss) private var dismiss
    @State private var boundChild: RootComponentChild?

    init(host: DefernoRoot, rawId: String) {
        _model = StateObject(wrappedValue: TaskDetailWindowModel(host: host, rawId: rawId))
        _rootStack = StateObject(wrappedValue: StateFlowObserver(host.root.activeChild))
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

/// Builds and OWNS one detached window's `TaskDetailWindowRoot` for the lifetime of its SwiftUI scene:
/// constructed at init (over the active session — `nil` when signed out), torn down in `deinit`
/// (`destroy()` → `lifecycle.destroy()`, so the window's component tree leaks nothing across open/close).
/// Republishes the stack's foreground detail + whether a level can be popped, on the main actor (the
/// component's `StateFlow` mirrors are bridged by SKIE, whose iterators run off the main thread).
final class TaskDetailWindowModel: ObservableObject {
    @Published private(set) var active: TaskDetailComponent?
    @Published private(set) var canGoBack = false
    /// The foreground entry's ref (e.g. "u-e4h2qk-1"), used as the window title. Falls back to the task
    /// title when the task has no ref (so the window is never blank), and re-points as you drill.
    @Published private(set) var title = ""

    private let windowRoot: TaskDetailWindowRoot?
    // `_Concurrency.Task`: `Deferno.Task` (the Kotlin model) shadows Swift's concurrency `Task` here.
    private var activeTask: _Concurrency.Task<Void, Never>?
    private var backTask: _Concurrency.Task<Void, Never>?
    private var titleTask: _Concurrency.Task<Void, Never>?

    init(host: DefernoRoot, rawId: String) {
        let root = TaskDetailWindowRootKt.openTaskDetailWindow(root: host.root, idValue: rawId)
        windowRoot = root
        if let root {
            active = root.activeDetail.value
            canGoBack = root.canGoBack.value.boolValue
            bindTitle(to: root.activeDetail.value)
            activeTask = _Concurrency.Task { [weak self] in
                for await component in root.activeDetail {
                    guard !_Concurrency.Task.isCancelled else { return }
                    await MainActor.run {
                        self?.active = component
                        self?.bindTitle(to: component)
                    }
                }
            }
            backTask = _Concurrency.Task { [weak self] in
                for await value in root.canGoBack {
                    guard !_Concurrency.Task.isCancelled else { return }
                    await MainActor.run { self?.canGoBack = value.boolValue }
                }
            }
        }
    }

    // Track the foreground detail's ref for the window title; re-subscribes on each push/pop.
    private func bindTitle(to component: TaskDetailComponent) {
        titleTask?.cancel()
        let flow = component.state
        title = Self.titleFor(flow.value)
        titleTask = _Concurrency.Task { [weak self] in
            for await state in flow {
                guard !_Concurrency.Task.isCancelled else { return }
                await MainActor.run { self?.title = Self.titleFor(state) }
            }
        }
    }

    private static func titleFor(_ state: TaskDetailState) -> String {
        state.task?.ref ?? state.task?.title ?? ""
    }

    deinit {
        activeTask?.cancel()
        backTask?.cancel()
        titleTask?.cancel()
        windowRoot?.destroy()
    }
}
