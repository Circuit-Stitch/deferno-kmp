import Combine
import Deferno

// Observe shared Kotlin state from SwiftUI through SKIE (ADR-0003): SKIE bridges each component's
// `StateFlow<T>` into an idiomatic Swift `SkieSwiftStateFlow<T>` (a synchronous `.value` + an
// `AsyncSequence`), so the observers below consume it directly — no hand-written Kotlin wrapper. This
// now includes **navigation**: the components expose their Decompose `Value`/`ChildStack`/`ChildSlot`
// as `StateFlow` mirrors of the active sealed child (`Value.asStateFlow`), which SKIE bridges like any
// other flow (sealed → Swift enum). The `@Published` mutation is hopped to the main actor (SKIE's flow
// iterator runs off the main thread), matching the prior main-thread invariant.

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
        task = _Concurrency.Task { @MainActor [weak self] in
            for await next in flow {
                guard !_Concurrency.Task.isCancelled, let self else { return }
                self.value = next
            }
        }
    }

    deinit { task?.cancel() }
}

/// Observes a component's **nullable** `StateFlow` (e.g. the Tasks detail slot, or the shell overlay)
/// via SKIE's `SkieSwiftOptionalStateFlow`. Mirrors `StateFlowObserver` with an optional `value`.
final class OptionalStateFlowObserver<T: AnyObject>: ObservableObject {
    @Published private(set) var value: T?
    // `_Concurrency.Task`: `Deferno.Task` (the Kotlin model) shadows Swift's concurrency `Task` here.
    private var task: _Concurrency.Task<Void, Never>?

    init(_ flow: SkieSwiftOptionalStateFlow<T>) {
        value = flow.value
        task = _Concurrency.Task { @MainActor [weak self] in
            for await next in flow {
                guard !_Concurrency.Task.isCancelled, let self else { return }
                self.value = next
            }
        }
    }

    deinit { task?.cancel() }
}
