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
    let onBrainDump: () -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.defernoColors) private var colors
    @StateObject private var destinations: StateFlowObserver<MainShellComponentDestinationChild>
    /// The dynamic nav registry (ADR-0040): the conditionally-present Assistant row appears once the Org is
    /// `entitled`, so observe it reactively rather than reading a fixed array.
    @StateObject private var navDestinations: DestinationsObserver
    @StateObject private var overlay: OptionalStateFlowObserver<MainShellComponentOverlayChild>
    @StateObject private var accounts: AccountsObserver
    @StateObject private var chrome: StateFlowObserver<ChromeSpec>
    @State private var showMore = false
    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    init(component: MainShellComponent, onBrainDump: @escaping () -> Void) {
        self.component = component
        self.onBrainDump = onBrainDump
        _destinations = StateObject(wrappedValue: StateFlowObserver(component.activeDestination))
        _navDestinations = StateObject(wrappedValue: DestinationsObserver(component.destinations))
        _overlay = StateObject(wrappedValue: OptionalStateFlowObserver(component.activeOverlay))
        _accounts = StateObject(wrappedValue: AccountsObserver(accounts: component.accounts, active: component.activeAccount))
        _chrome = StateObject(wrappedValue: StateFlowObserver(component.chrome))
    }

    private var active: MainShellComponentDestinationChild { destinations.value }
    private var activeName: String { ShellBridgeKt.destinationName(destination: ShellBridgeKt.destinationOf(child: active)) }

    // The single adaptive chrome (Cand 1), computed in the shell. `drilled` swaps the leading affordance
    // (sidebar/account ↔ ← back) and hides the create actions; `barTitle` is the foreground surface title,
    // falling back to the nav label for Tasks (its co-resident panes carry their own headers, so
    // chrome.title is empty — the documented carve-out).
    private var drilled: Bool { ShellBridgeKt.chromeDrilled(spec: chrome.value) }
    private var barTitle: String {
        let title = ShellBridgeKt.chromeTitle(spec: chrome.value)
        return title.isEmpty ? activeName : title
    }

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
        NavigationSplitView(columnVisibility: $columnVisibility) {
            sidebar
        } detail: {
            destinationBody
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .toolbar { windowToolbar }
                // The title bar tracks the foreground surface from the shell-computed ChromeSpec (Cand 1):
                // the Destination at a root ("Today" / "Calendar" …) and the drilled detail's own title (a
                // Task name, a Settings category) when drilled — the macOS-idiomatic sidebar-app title. The
                // window itself stays named "Deferno" (Window scene + Dock icon) for ⌘-tab / the Window menu.
                .navigationTitle(barTitle)
        }
    }

    /// The window's native title-bar actions (macOS): the account switcher sits leading next to the
    /// sidebar toggle; Search · Brain dump · New task trail to the top-right (New rightmost). This is
    /// the desktop counterpart of the Android shell chrome's trailing glyph row — Brain dump opens the
    /// on-device Extractor (the same sheet as the ⌘⇧E menu), New mirrors the FAB's pre-dating behaviour.
    @ToolbarContentBuilder
    private var windowToolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            Button {
                withAnimation { columnVisibility = columnVisibility == .detailOnly ? .all : .detailOnly }
            } label: {
                Image(systemName: "sidebar.leading")
            }
            .help("Toggle Sidebar")
        }
        // Drilled into a tier-3 detail (Plan task / Settings category): a ← back that pops via the shell's
        // onBack, mirroring the iOS/Android chrome's leading affordance.
        if drilled {
            ToolbarItem(placement: .navigation) {
                Button { _ = component.onBack() } label: { Image(systemName: "chevron.backward") }
                    .help("Back")
                    .accessibilityLabel("Back")
            }
        }
        if accounts.accounts.count > 1 {
            ToolbarItem(placement: .navigation) { accountSwitcher }
        }
        ToolbarItemGroup(placement: .primaryAction) {
            Button { ShellBridgeKt.openSearchOverlay(component: component) } label: {
                Image(systemName: "magnifyingglass")
            }
            .help("Search")
            // Create actions belong to a Destination root, not a drilled detail (matches ChromeSpec.actions).
            if !drilled {
                Button(action: onBrainDump) {
                    Image(systemName: "brain.head.profile")
                }
                .help("Brain dump")
                Button(action: onNewTapped) {
                    Image(systemName: "plus")
                }
                .help("New task")
            }
        }
    }

    private var bodyWithFab: some View {
        ZStack(alignment: .bottomTrailing) {
            destinationBody
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            // No create affordance when drilled into a detail (matches ChromeSpec.actions / the toolbar).
            if !drilled {
                newFab
                    .padding(Layout.gutter)
            }
        }
    }

    // MARK: Active Destination body

    @ViewBuilder
    private var destinationBody: some View {
        let child = active
        if let plan = ShellBridgeKt.destPlan(child: child) {
            PlanHostView(plan: plan)
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
            if drilled {
                Button { _ = component.onBack() } label: {
                    Image(systemName: "chevron.backward").font(.title3).foregroundStyle(colors.onSurface)
                }
                .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
                .accessibilityLabel("Back")
            } else if accounts.accounts.count > 1 {
                accountSwitcher
            }
            // The foreground surface title (Cand 1) — the compact twin of the regular layout's window title.
            Text(barTitle)
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.tail)
                .foregroundStyle(colors.onSurface)
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
        // Always-labelled rows. The min column width fits a full label so they never truncate; collapse
        // the whole sidebar (the toolbar toggle) when you want it out of the way, rather than an icon rail.
        List {
            ForEach(allDestinations) { dest in
                let name = ShellBridgeKt.destinationName(destination: dest)
                let selected = name == activeName
                Button { component.selectDestination(destination: dest) } label: {
                    Label(name, systemImage: icon(name))
                        .foregroundStyle(selected ? colors.primary : colors.onSurface)
                }
                .help(name)
                .listRowBackground(selected ? colors.primaryContainer : Color.clear)
            }
        }
        .listStyle(.sidebar)
        .navigationSplitViewColumnWidth(min: 160, ideal: 220, max: 320)
        // Drop the auto sidebar toggle (generated for the sidebar column) — it jumped to the toolbar's
        // trailing `»` overflow when collapsed. windowToolbar supplies our own, pinned leading instead.
        .toolbar(removing: .sidebarToggle)
    }

    // MARK: Overlay (Search / New) as a sheet

    private var overlaySearchComponent: SearchComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlaySearch(child: $0) }
    }

    private var overlayNewComponent: NewComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlayNew(child: $0) }
    }

    private var overlayFeedbackComponent: FeedbackComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlayFeedback(child: $0) }
    }

    private var overlayPresented: Binding<Bool> {
        Binding(
            get: { overlaySearchComponent != nil || overlayNewComponent != nil || overlayFeedbackComponent != nil },
            set: { presented in if !presented { component.dismissOverlay() } }
        )
    }

    @ViewBuilder
    private var overlayContent: some View {
        if let search = overlaySearchComponent {
            SearchView(component: search)
        } else if let new = overlayNewComponent {
            NewItemView(component: new)
        } else if let feedback = overlayFeedbackComponent {
            FeedbackView(component: feedback)
        }
    }

    // MARK: Destination registry helpers

    private var allDestinations: [Destination] { navDestinations.destinations }
    private var primaryDestinations: [Destination] {
        navDestinations.destinations.filter { ShellBridgeKt.destinationIsPrimary(destination: $0) }
    }
    private var secondaryDestinations: [Destination] {
        navDestinations.destinations.filter { !ShellBridgeKt.destinationIsPrimary(destination: $0) }
    }

    private func icon(_ name: String) -> String {
        switch name {
        case "Plan": return "house.fill"
        case "Calendar": return "calendar"
        case "Tasks": return "list.bullet"
        case "Assistant": return "sparkles"
        case "Profile": return "person.fill"
        case "Settings": return "gearshape.fill"
        default: return "circle"
        }
    }
}
