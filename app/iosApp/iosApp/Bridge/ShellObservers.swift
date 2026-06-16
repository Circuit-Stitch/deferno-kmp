import Combine
import Deferno

// SwiftUI-observable wrappers for the **shell half** of the SKIE-free bridge (#35) — the navigation
// frame's Decompose `ChildStack`/`ChildSlot` containers and the new Destinations' sealed states. They
// mirror the `ObservableState.swift` pattern (seed synchronously, republish on the Kotlin main
// dispatcher, cancel in `deinit`), but are **concrete** (not generic): each wrapped child is a Kotlin
// `sealed interface`, which Obj-C lightweight generics can't carry as a type argument. When SKIE lands
// (ADR-0003) this file and the Kotlin `ShellBridge.kt` can both be deleted.

/// Observes the root Auth↔Main stack: the single active `RootComponent.Child`.
final class RootStackObserver: ObservableObject {
    @Published private(set) var active: RootComponentChild
    private var subscription: Deferno.Subscription?

    init(_ bridge: RootStackBridge) {
        active = bridge.active
        subscription = bridge.subscribe(onEach: { [weak self] child in
            self?.active = child
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the Main Destination stack: the foreground `MainShellComponent.DestinationChild`.
final class DestinationStackObserver: ObservableObject {
    @Published private(set) var active: MainShellComponentDestinationChild
    private var subscription: Deferno.Subscription?

    init(_ bridge: DestinationStackBridge) {
        active = bridge.active
        subscription = bridge.subscribe(onEach: { [weak self] child in
            self?.active = child
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the shell overlay slot: the open `MainShellComponent.OverlayChild`, or `nil`.
final class OverlaySlotObserver: ObservableObject {
    @Published private(set) var current: MainShellComponentOverlayChild?
    private var subscription: Deferno.Subscription?

    init(_ bridge: OverlaySlotBridge) {
        current = bridge.current
        subscription = bridge.subscribe(onEach: { [weak self] child in
            self?.current = child
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the Settings tier-3 drill-down stack: the active `SettingsComponent.SettingsChild`.
final class SettingsStackObserver: ObservableObject {
    @Published private(set) var active: SettingsComponentSettingsChild
    private var subscription: Deferno.Subscription?

    init(_ bridge: SettingsStackBridge) {
        active = bridge.active
        subscription = bridge.subscribe(onEach: { [weak self] child in
            self?.active = child
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the Plan Destination's tier-3 drill-down stack (#51): the active `MainShellComponent.PlanChild`
/// (the Dashboard base or a drilled-in Task detail). Mirrors `SettingsStackObserver`.
final class PlanStackObserver: ObservableObject {
    @Published private(set) var active: MainShellComponentPlanChild
    private var subscription: Deferno.Subscription?

    init(_ bridge: PlanStackBridge) {
        active = bridge.active
        subscription = bridge.subscribe(onEach: { [weak self] child in
            self?.active = child
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the Profile Destination's sealed `ProfileState`.
final class ProfileStateObserver: ObservableObject {
    @Published private(set) var value: ProfileState
    private var subscription: Deferno.Subscription?

    init(_ bridge: ProfileStateBridge) {
        value = bridge.value
        subscription = bridge.subscribe(onEach: { [weak self] next in
            self?.value = next
        })
    }

    deinit { subscription?.cancel() }
}

/// Observes the in-shell account switcher: the roster + the Active Account (re-read on either change).
final class AccountsObserver: ObservableObject {
    @Published private(set) var accounts: [Account]
    @Published private(set) var active: Account?
    private let bridge: AccountSwitcherBridge
    private var subscription: Deferno.Subscription?

    init(_ bridge: AccountSwitcherBridge) {
        self.bridge = bridge
        accounts = bridge.accounts
        active = bridge.active
        subscription = bridge.subscribe(onChange: { [weak self] in
            guard let self else { return }
            self.accounts = self.bridge.accounts
            self.active = self.bridge.active
        })
    }

    deinit { subscription?.cancel() }
}
