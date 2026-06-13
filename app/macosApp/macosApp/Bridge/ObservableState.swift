import Combine
import Deferno

// Observe shared Kotlin state from SwiftUI **without SKIE** (#51). SKIE (ADR-0003) would expose the
// components' `StateFlow`/`Value` as idiomatic Swift, but no released SKIE supports Kotlin 2.4.0 yet
// (see ../README.md). Until it ships, the Kotlin side hands us small callback-based bridges
// (`StateFlowBridge`/`ValueBridge`/`DetailSlot`/`TreeSlot` in `…/ios/bridge/Bridge.kt`); these
// `ObservableObject` wrappers turn each into a SwiftUI-observable value. The bridges publish on the
// Kotlin main dispatcher (the iOS main thread), so `@Published` mutations happen on the main thread.
// When SKIE lands, this file and the Kotlin bridge can both be deleted.

/// Observes a component's `StateFlow` (its pane state) as published SwiftUI state.
final class StateFlowObserver<T: AnyObject>: ObservableObject {
    @Published private(set) var value: T
    private var subscription: Deferno.Subscription?

    init(_ bridge: StateFlowBridge<T>) {
        value = bridge.value
        subscription = bridge.subscribe(onEach: { [weak self] next in
            self?.value = next
        })
    }

    deinit { subscription?.cancel() }
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

/// Observes the Tasks **tree** co-resident slot: the open tree component, or `nil`.
final class TreeSlotObserver: ObservableObject {
    @Published private(set) var current: TaskTreeComponent?
    private var subscription: Deferno.Subscription?

    init(_ slot: TreeSlot) {
        current = slot.current
        subscription = slot.subscribe(onEach: { [weak self] component in
            self?.current = component
        })
    }

    deinit { subscription?.cancel() }
}
