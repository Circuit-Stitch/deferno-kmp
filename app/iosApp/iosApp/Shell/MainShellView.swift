import Deferno
import SwiftUI

/// The Main shell — the SwiftUI twin of Android/desktop's shared `ShellChrome` (ADR-0013/0017). It is a
/// **reveal drawer**: a slim top bar carries a hamburger menu toggle (start) and the New action (end),
/// and toggling the menu slides the whole content card aside to expose a navigation menu sitting
/// *underneath* it. The menu lists an Account header (a switcher when >1 Account, else a label), a
/// Search row, then one row per Destination — the same layout as Android's `ShellDrawer`, so there is no
/// bottom tab bar and no Primary/Secondary split. One chrome for every size class (never a device check,
/// ADR-0008); on a wide window the drawer just caps its width and leaves more content peeking.
///
/// The active Destination, its retained per-Destination state, and the shell overlay all live in the
/// shared `MainShellComponent` — this View holds only the local drawer open/drag state. The hamburger
/// opens the drawer; tapping the dimmed content or dragging it back toward the edge closes it (the
/// finger is tracked 1:1, then settles to the nearer end on release).
struct MainShellView: View {
    let component: MainShellComponent
    @Environment(\.defernoColors) private var colors
    @StateObject private var destinations: DestinationStackObserver
    @StateObject private var overlay: OverlaySlotObserver
    @StateObject private var accounts: AccountsObserver
    @State private var drawerOpen = false
    /// Non-nil only while a finger is dragging the open content back toward the edge — makes the drawer
    /// track the finger 1:1. The effective open fraction is `dragX != nil ? base + dragX/width : base`.
    @State private var dragX: CGFloat? = nil

    init(component: MainShellComponent) {
        self.component = component
        _destinations = StateObject(wrappedValue: DestinationStackObserver(ShellBridgeKt.destinationStackBridge(component: component)))
        _overlay = StateObject(wrappedValue: OverlaySlotObserver(ShellBridgeKt.overlaySlotBridge(component: component)))
        _accounts = StateObject(wrappedValue: AccountsObserver(ShellBridgeKt.accountSwitcherBridge(component: component)))
    }

    private var active: MainShellComponentDestinationChild { destinations.active }
    private var activeName: String { ShellBridgeKt.destinationName(destination: ShellBridgeKt.destinationOf(child: active)) }

    var body: some View {
        GeometryReader { geo in
            // Wide enough to read as a drawer but always leaving a peek of content; capped so a big iPad
            // window doesn't get an absurdly wide menu (mirrors ShellChrome's 0.82f / 320dp).
            let drawerWidth = min(geo.size.width * 0.82, 320)
            let fraction = openFraction(drawerWidth)
            ZStack(alignment: .leading) {
                drawer
                    .frame(width: drawerWidth)
                    .frame(maxHeight: .infinity)
                content
                    .frame(width: geo.size.width, height: geo.size.height)
                    .shadow(color: .black.opacity(0.18 * fraction), radius: 8, x: -2)
                    .offset(x: fraction * drawerWidth)
                    .overlay { if drawerOpen { scrim(drawerWidth, fraction: fraction) } }
            }
        }
        .background(colors.background.ignoresSafeArea())
        .sheet(isPresented: overlayPresented) { overlayContent }
    }

    // MARK: Open fraction (0 closed … 1 open)

    private func openFraction(_ drawerWidth: CGFloat) -> CGFloat {
        let base: CGFloat = drawerOpen ? 1 : 0
        guard let dragX else { return base }
        return min(max(base + dragX / drawerWidth, 0), 1)
    }

    // MARK: Content card (top bar + active Destination), drawn on top of the drawer

    private var content: some View {
        VStack(spacing: 0) {
            topBar
            destinationBody.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(colors.background)
    }

    private var topBar: some View {
        HStack(spacing: 4) {
            Button { withAnimation(.easeOut(duration: 0.25)) { drawerOpen.toggle() } } label: {
                Image(systemName: "line.3.horizontal")
                    .font(.title3)
                    .foregroundStyle(colors.onSurface)
            }
            .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
            .accessibilityLabel("Menu")
            Spacer()
            Button { onNewTapped() } label: {
                Image(systemName: "plus")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(colors.onSurface)
            }
            .frame(minWidth: Layout.minTouchTarget, minHeight: Layout.minTouchTarget)
            .accessibilityLabel("New")
        }
        .padding(.horizontal, 4)
        .frame(minHeight: 48)
        .background(colors.surface)
    }

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

    private func onNewTapped() {
        // On Calendar the New action pre-dates to the selected day (#74); elsewhere it opens an undated form.
        if let calendar = ShellBridgeKt.destCalendar(child: active) {
            calendar.onNewForSelectedDay()
        } else {
            ShellBridgeKt.openNewOverlay(component: component)
        }
    }

    // MARK: Reveal drawer (Account header → Search → Destinations), drawn underneath the content

    private var drawer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                accountHeader
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                drawerRow(label: "Search", system: "magnifyingglass", selected: false) {
                    closeDrawer()
                    ShellBridgeKt.openSearchOverlay(component: component)
                }
                ForEach(component.destinations) { dest in
                    let name = ShellBridgeKt.destinationName(destination: dest)
                    drawerRow(label: name, system: icon(name), selected: name == activeName) {
                        closeDrawer()
                        component.selectDestination(destination: dest)
                    }
                }
            }
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxHeight: .infinity)
        .background(colors.surfaceVariant.ignoresSafeArea())
    }

    @ViewBuilder
    private var accountHeader: some View {
        if accounts.accounts.count > 1 {
            Menu {
                ForEach(accounts.accounts) { account in
                    Button(account.label) { ShellBridgeKt.switchToAccount(component: component, account: account) }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(accounts.active?.label ?? "Select account").font(.headline)
                    Image(systemName: "chevron.down").font(.caption2)
                }
                .foregroundStyle(colors.onSurface)
            }
            .accessibilityLabel("Switch account")
        } else {
            Text(accounts.active?.label ?? "Deferno")
                .font(.headline)
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func drawerRow(label: String, system: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: system).font(.system(size: 20)).frame(width: 24)
                Text(label).font(.body)
                Spacer()
            }
            .padding(.horizontal, 16)
            .frame(minHeight: Layout.minTouchTarget)
            .foregroundStyle(selected ? colors.primary : colors.onSurface)
            .background(selected ? colors.primaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: 24))
            .contentShape(Rectangle())
        }
        .padding(.horizontal, 12)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Drawer open/close

    private func closeDrawer() {
        withAnimation(.easeOut(duration: 0.25)) { drawerOpen = false }
    }

    /// The dimmed slid-aside content: tap to close, or drag it back toward the edge (finger-tracking,
    /// settling to the nearer end on release).
    private func scrim(_ drawerWidth: CGFloat, fraction: CGFloat) -> some View {
        Color.black.opacity(0.32 * fraction)
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .onTapGesture { closeDrawer() }
            .gesture(
                DragGesture(minimumDistance: 5)
                    .onChanged { dragX = min($0.translation.width, 0) }
                    .onEnded { value in
                        let predicted = 1 + value.predictedEndTranslation.width / drawerWidth
                        withAnimation(.easeOut(duration: 0.25)) {
                            drawerOpen = predicted >= 0.5
                            dragX = nil
                        }
                    }
            )
    }

    // MARK: Overlay (Search / New / Plan-tapped Task detail) as a sheet

    private var overlaySearchComponent: SearchComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlaySearch(child: $0) }
    }

    private var overlayNewComponent: NewComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlayNew(child: $0) }
    }

    private var overlayTaskDetailComponent: TaskDetailComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlayTaskDetail(child: $0) }
    }

    private var overlayPresented: Binding<Bool> {
        Binding(
            get: { overlaySearchComponent != nil || overlayNewComponent != nil || overlayTaskDetailComponent != nil },
            set: { presented in if !presented { component.dismissOverlay() } }
        )
    }

    @ViewBuilder
    private var overlayContent: some View {
        if let search = overlaySearchComponent {
            SearchView(component: search)
        } else if let new = overlayNewComponent {
            NewItemView(component: new)
        } else if let task = overlayTaskDetailComponent {
            // A Plan tap shows the Task here, over the dashboard, instead of switching to the Tasks tab.
            TaskDetailView(component: task)
        }
    }

    // MARK: Destination glyphs (a View concern, mirrors ShellChrome's Destination.icon)

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
