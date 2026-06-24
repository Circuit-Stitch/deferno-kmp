import Combine
import Deferno

// Observe shared Kotlin state from SwiftUI. **StateFlow** observation is now done through SKIE
// (ADR-0003): SKIE bridges each component's `StateFlow<T>` into an idiomatic Swift
// `SkieSwiftStateFlow<T>` (a synchronous `.value` + an `AsyncSequence`), so `StateFlowObserver` below
// consumes it directly — no hand-written Kotlin wrapper. The `@Published` mutation is hopped to the
// main actor (SKIE's flow iterator runs off the main thread), matching the prior main-thread invariant.
//
// **Decompose** `Value`/`ChildStack`/`ChildSlot` (the navigation containers) and the co-resident detail
// `ChildSlot` are NOT bridged by SKIE — those keep their hand-written, callback-based bridges
// (`DetailSlot` in `…/ios/bridge/Bridge.kt`, the `*StackBridge`/`*SlotBridge` in `ShellBridge.kt`),
// wrapped by the `ObservableObject`s below + in `ShellObservers.swift`. Those publish on the Kotlin
// main dispatcher, so their `@Published` mutations already happen on the main thread.

/// Observes a component's `StateFlow` (its pane state) as published SwiftUI state, via SKIE's
/// `SkieSwiftStateFlow`. Seeds synchronously from `.value`, then republishes each emission on the main
/// actor; the collecting `Task` is cancelled in `deinit`.
final class StateFlowObserver<T: AnyObject>: ObservableObject {
    @Published private(set) var value: T
    // `_Concurrency.Task`: the framework exports `Deferno.Task` (the Kotlin model), which would
    // otherwise shadow Swift's concurrency `Task` here.
    private var task: _Concurrency.Task<Void, Never>?

    init(_ flow: SkieSwiftStateFlow<T>) {
        value = flow.value
        task = _Concurrency.Task { [weak self] in
            for await next in flow {
                guard !_Concurrency.Task.isCancelled else { return }
                await MainActor.run { self?.value = next }
            }
        }
    }

    deinit { task?.cancel() }
}

/// Observes a Decompose `Value` (e.g. the Tasks `activePane`) as published SwiftUI state.
final class ValueObserver<T: AnyObject>: ObservableObject {
    @Published private(set) var value: T
    private var subscription: Deferno.Subscription?

    init(_ bridge: ValueBridge<T>) {
        value = bridge.current
        subscription = bridge.subscribe(onEach: { [weak self] next in
            self?.value = next
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the Tasks **detail** co-resident slot: the open detail component, or `nil`.
final class DetailSlotObserver: ObservableObject {
    @Published private(set) var current: TaskDetailComponent?
    private var subscription: Deferno.Subscription?

    init(_ slot: DetailSlot) {
        current = slot.current
        subscription = slot.subscribe(onEach: { [weak self] component in
            self?.current = component
        })
    }

    deinit { subscription?.cancel() }
}
