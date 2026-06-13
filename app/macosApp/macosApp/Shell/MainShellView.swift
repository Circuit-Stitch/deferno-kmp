import Deferno
import SwiftUI

/// The Main shell — the SwiftUI twin of Android's `MainShell` (ADR-0013/0015). It renders the shared
/// `MainShellComponent`'s Destination graph as an **adaptive nav suite** driven purely by the
/// horizontal size class (never a device check, ADR-0008):
///   • compact (iPhone): a bottom bar of the three Primary Destinations + a **More** overflow onto the
///     Secondary ones, over a single active-Destination body;
///   • regular (iPad): a `NavigationSplitView` whose sidebar lists all five Destinations.
/// Above the body sit the shell chrome (account switcher when >1 account, global Search ⌕) and the New
/// FAB; the shell-level overlay (Search / New) presents as a sheet. The active Destination, its
/// retained per-Destination state, and the overlay all live in the shared component — this View holds
/// only the local "More" sheet flag.
struct MainShellView: View {
    let component: MainShellComponent
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.defernoColors) private var colors
    @StateObject private var destinations: DestinationStackObserver
    @StateObject private var overlay: OverlaySlotObserver
    @StateObject private var accounts: AccountsObserver
    @State private var showMore = false

    init(component: MainShellComponent) {
        self.component = component
        _destinations = StateObject(wrappedValue: DestinationStackObserver(ShellBridgeKt.destinationStackBridge(component: component)))
        _overlay = StateObject(wrappedValue: OverlaySlotObserver(ShellBridgeKt.overlaySlotBridge(component: component)))
        _accounts = StateObject(wrappedValue: AccountsObserver(ShellBridgeKt.accountSwitcherBridge(component: component)))
    }

    private var active: MainShellComponentDestinationChild { destinations.active }
    private var activeName: String { ShellBridgeKt.destinationName(destination: ShellBridgeKt.destinationOf(child: active)) }

    var body: some View {
        Group {
            if sizeClass != .compact {
                regularLayout
            } else {
                compactLayout
            }
        }
        .background(colors.background.ignoresSafeArea())
        .sheet(isPresented: overlayPresented) { overlayContent }
    }

    // MARK: Layouts

    private var compactLayout: some View {
        VStack(spacing: 0) {
            topBar
            bodyWithFab
            bottomBar
        }
    }

    private var regularLayout: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            VStack(spacing: 0) {
                topBar
                bodyWithFab
            }
        }
    }

    private var bodyWithFab: some View {
        ZStack(alignment: .bottomTrailing) {
            destinationBody
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            newFab
                .padding(Layout.gutter)
        }
    }

    // MARK: Active Destination body

    @ViewBuilder
    private var destinationBody: some View {
        let child = active
        if let plan = ShellBridgeKt.destPlan(child: child) {
            PlanView(component: plan)
        } else if let calendar = ShellBridgeKt.destCalendar(child: child) {
            CalendarView(component: calendar)
        } else if let tasks = ShellBridgeKt.destTasks(child: child) {
            TasksScreen(root: ShellBridgeKt.tasksRoot(component: tasks))
        } else if let profile = ShellBridgeKt.destProfile(child: child) {
            ProfileView(component: profile)
        } else if let settings = ShellBridgeKt.destSettings(child: child) {
            SettingsView(component: settings)
        } else {
            EmptyStateView(title: "\(activeName) is coming soon", message: "This area is on the way.")
        }
    }

    // MARK: Shell chrome

    private var topBar: some View {
        HStack(spacing: 8) {
            if accounts.accounts.count > 1 {
                accountSwitcher
            }
            Spacer()
            Button { ShellBridgeKt.openSearchOverlay(component: component) } label: {
                Image(systemName: "magnifyingglass")
                    .font(.title3)
                    .foregroundStyle(colors.onSurface)
            }
            .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
            .accessibilityLabel("Search")
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .background(colors.surface)
    }

    private var accountSwitcher: some View {
        Menu {
            ForEach(accounts.accounts) { account in
                Button(account.label) { ShellBridgeKt.switchToAccount(component: component, account: account) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(accounts.active?.label ?? "Select account")
                    .font(.subheadline.weight(.medium))
                Image(systemName: "chevron.down").font(.caption2)
            }
            .foregroundStyle(colors.onSurface)
        }
        .accessibilityLabel("Switch account")
    }

    private var newFab: some View {
        Button { onNewTapped() } label: {
            Image(systemName: "plus")
                .font(.title2.weight(.semibold))
                .foregroundStyle(colors.onPrimary)
                .frame(width: 56, height: 56)
                .background(colors.primary, in: Circle())
                .shadow(radius: 4, y: 2)
        }
        .accessibilityLabel("New")
    }

    private func onNewTapped() {
        // On Calendar the FAB pre-dates New to the selected day (#74); elsewhere it opens an undated form.
        if let calendar = ShellBridgeKt.destCalendar(child: active) {
            calendar.onNewForSelectedDay()
        } else {
            ShellBridgeKt.openNewOverlay(component: component)
        }
    }

    // MARK: Bottom bar (compact) — 3 Primary + More overflow

    private var bottomBar: some View {
        HStack(spacing: 0) {
            ForEach(primaryDestinations) { dest in
                barItem(dest)
            }
            moreItem
        }
        .frame(height: 52)
        .background(colors.surface)
        .overlay(Rectangle().frame(height: 0.5).foregroundStyle(colors.outlineVariant), alignment: .top)
    }

    @ViewBuilder
    private func barItem(_ dest: Destination) -> some View {
        let name = ShellBridgeKt.destinationName(destination: dest)
        let selected = name == activeName
        Button { component.selectDestination(destination: dest) } label: {
            navItemLabel(name: name, system: icon(name), selected: selected)
        }
        .accessibilityLabel(name)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var moreItem: some View {
        let selected = !ShellBridgeKt.destinationIsPrimary(destination: ShellBridgeKt.destinationOf(child: active))
        return Button { showMore = true } label: {
            navItemLabel(name: "More", system: "ellipsis", selected: selected)
        }
        .accessibilityLabel("More")
        .confirmationDialog("More", isPresented: $showMore, titleVisibility: .visible) {
            ForEach(secondaryDestinations) { dest in
                Button(ShellBridgeKt.destinationName(destination: dest)) {
                    component.selectDestination(destination: dest)
                }
            }
        }
    }

    private func navItemLabel(name: String, system: String, selected: Bool) -> some View {
        VStack(spacing: 3) {
            Image(systemName: system).font(.system(size: 20))
            Text(name).font(.caption2)
        }
        .foregroundStyle(selected ? colors.primary : colors.inkMuted)
        .frame(maxWidth: .infinity)
        .frame(minHeight: Layout.minTouchTarget)
        .contentShape(Rectangle())
    }

    // MARK: Sidebar (regular)

    private var sidebar: some View {
        List {
            ForEach(allDestinations) { dest in
                let name = ShellBridgeKt.destinationName(destination: dest)
                let selected = name == activeName
                Button { component.selectDestination(destination: dest) } label: {
                    Label(name, systemImage: icon(name))
                        .foregroundStyle(selected ? colors.primary : colors.onSurface)
                }
                .listRowBackground(selected ? colors.primaryContainer : Color.clear)
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("Deferno")
    }

    // MARK: Overlay (Search / New) as a sheet

    private var overlaySearchComponent: SearchComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlaySearch(child: $0) }
    }

    private var overlayNewComponent: NewComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlayNew(child: $0) }
    }

    private var overlayPresented: Binding<Bool> {
        Binding(
            get: { overlaySearchComponent != nil || overlayNewComponent != nil },
            set: { presented in if !presented { component.dismissOverlay() } }
        )
    }

    @ViewBuilder
    private var overlayContent: some View {
        if let search = overlaySearchComponent {
            SearchView(component: search)
        } else if let new = overlayNewComponent {
            NewItemView(component: new)
        }
    }

    // MARK: Destination registry helpers

    private var allDestinations: [Destination] { component.destinations }
    private var primaryDestinations: [Destination] {
        component.destinations.filter { ShellBridgeKt.destinationIsPrimary(destination: $0) }
    }
    private var secondaryDestinations: [Destination] {
        component.destinations.filter { !ShellBridgeKt.destinationIsPrimary(destination: $0) }
    }

    private func icon(_ name: String) -> String {
        switch name {
        case "Plan": return "house.fill"
        case "Calendar": return "calendar"
        case "Tasks": return "list.bullet"
        case "Profile": return "person.fill"
        case "Settings": return "gearshape.fill"
        default: return "circle"
        }
    }
}
