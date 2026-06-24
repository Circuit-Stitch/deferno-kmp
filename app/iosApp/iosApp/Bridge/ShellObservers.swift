import Combine
import Deferno

// SwiftUI-observable wrappers for the **shell half** of the bridge — the Destinations' bespoke
// `StateFlow` states. The navigation containers (Root Auth↔Main, the Destination stack, the overlay
// slot, the Settings/Plan drill-downs) used to live here as concrete `*StackBridge` observers; they're
// gone now that each component exposes its Decompose `Value` as a `StateFlow` of the active sealed
// child (`Value.asStateFlow`), so SKIE bridges them and the Views observe with the generic
// `StateFlowObserver`/`OptionalStateFlowObserver` (`ObservableState.swift`). What remains are the two
// observers with a shape those generics don't cover: `ProfileState`, and the two-flow account switcher.

/// Observes the Profile Destination's sealed `ProfileState` (a `StateFlow`, via SKIE). Mirrors
/// `StateFlowObserver`; ProfileState is exported, so SKIE bridges the flow directly.
final class ProfileStateObserver: ObservableObject {
    @Published private(set) var value: ProfileState
    // `_Concurrency.Task`: `Deferno.Task` (the Kotlin model) shadows Swift's concurrency `Task` here.
    private var task: _Concurrency.Task<Void, Never>?

    init(_ flow: SkieSwiftStateFlow<ProfileState>) {
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

/// Observes the in-shell account switcher: the roster + the Active Account (each a `StateFlow`, via
/// SKIE). Two collecting tasks — one per flow — both republished on the main actor.
final class AccountsObserver: ObservableObject {
    @Published private(set) var accounts: [Account]
    @Published private(set) var active: Account?
    // `_Concurrency.Task`: `Deferno.Task` (the Kotlin model) shadows Swift's concurrency `Task` here.
    private var accountsTask: _Concurrency.Task<Void, Never>?
    private var activeTask: _Concurrency.Task<Void, Never>?

    init(accounts accountsFlow: SkieSwiftStateFlow<[Account]>, active activeFlow: SkieSwiftOptionalStateFlow<Account>) {
        accounts = accountsFlow.value
        active = activeFlow.value
        accountsTask = _Concurrency.Task { @MainActor [weak self] in
            for await next in accountsFlow {
                guard !_Concurrency.Task.isCancelled, let self else { return }
                self.accounts = next
            }
        }
        activeTask = _Concurrency.Task { @MainActor [weak self] in
            for await next in activeFlow {
                guard !_Concurrency.Task.isCancelled, let self else { return }
                self.active = next
            }
        }
    }

    deinit {
        accountsTask?.cancel()
        activeTask?.cancel()
    }
}
