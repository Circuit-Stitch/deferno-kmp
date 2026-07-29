import Deferno
import SwiftUI

/// The Main shell — the SwiftUI twin of Android's `MainShell` (ADR-0013/0015). macOS has no compact size
/// class, so there is exactly ONE layout: a `NavigationSplitView` whose sidebar lists every Destination the
/// shared registry publishes, with the active Destination filling the detail column under the window's own
/// title bar. (#368 G21b deleted the phone-shaped chrome this file had inherited from `iosApp` — a bottom
/// bar of Primary Destinations, a "More" overflow onto the Secondary ones, and a New FAB — all of it
/// gated on `horizontalSizeClass != .compact`, i.e. unreachable on macOS and misleading in a diff.)
/// The title bar carries the shell chrome: the sidebar toggle, the drilled ← back and the account switcher
/// (when >1 Account) lead; Search plus the shell-computed `ChromeSpec.actions` trail. The shell-level
/// overlay (Search / New / Feedback) presents as a sheet. The active Destination, its retained
/// per-Destination state, and the overlay all live in the shared component — this View holds only the
/// local sidebar-visibility flag.
struct MainShellView: View {
    let component: MainShellComponent
    let onBrainDump: () -> Void
    @Environment(\.defernoColors) private var colors
    @StateObject private var destinations: StateFlowObserver<MainShellComponentDestinationChild>
    /// The dynamic nav registry (ADR-0040): the conditionally-present Assistant row appears once the Org is
    /// `entitled`, so observe it reactively rather than reading a fixed array.
    @StateObject private var navDestinations: DestinationsObserver
    @StateObject private var overlay: OptionalStateFlowObserver<MainShellComponentOverlayChild>
    @StateObject private var accounts: AccountsObserver
    @StateObject private var chrome: StateFlowObserver<ChromeSpec>
    /// The Active Account's session-expired flag (#297) — drives the read-surface "Session expired" banner.
    @StateObject private var sessionExpired: StateFlowObserver<KotlinBoolean>
    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    init(component: MainShellComponent, onBrainDump: @escaping () -> Void) {
        self.component = component
        self.onBrainDump = onBrainDump
        _destinations = StateObject(wrappedValue: StateFlowObserver(component.activeDestination))
        _navDestinations = StateObject(wrappedValue: DestinationsObserver(component.destinations))
        _overlay = StateObject(wrappedValue: OptionalStateFlowObserver(component.activeOverlay))
        _accounts = StateObject(wrappedValue: AccountsObserver(accounts: component.accounts, active: component.activeAccount))
        _chrome = StateObject(wrappedValue: StateFlowObserver(component.chrome))
        _sessionExpired = StateObject(wrappedValue: StateFlowObserver(component.sessionExpired))
    }

    private var active: MainShellComponentDestinationChild { destinations.value }
    /// The raw bridge token of the active Destination — for identity comparisons only.
    private var activeRawName: String { ShellBridgeKt.destinationName(destination: ShellBridgeKt.destinationOf(child: active)) }
    /// The localized label of the active Destination — for display.
    private var activeName: String { L.destinationLabel(activeRawName) }

    // The single adaptive chrome (Cand 1), computed in the shell. `drilled` swaps the leading affordance
    // (sidebar/account ↔ ← back) and hides the create actions; `barTitle` is the foreground surface title,
    // falling back to the nav label for Tasks (its co-resident panes carry their own headers, so
    // chrome.title is empty — the documented carve-out).
    private var drilled: Bool { ShellBridgeKt.chromeDrilled(spec: chrome.value) }
    private var barTitle: String {
        let title = L.chromeTitle(chrome.value)
        return title.isEmpty ? activeName : title
    }

    /// Whether the current spec still carries the capture pair (Brain dump + New). It is dropped while the
    /// Tasks tree is in move mode — see the carve-out in `windowToolbar`.
    private var specHasCapture: Bool {
        (0..<Int(ShellBridgeKt.chromeActionCount(spec: chrome.value))).contains {
            ShellBridgeKt.chromeActionKind(spec: chrome.value, index: Int32($0)) == "New"
        }
    }

    var body: some View {
        splitLayout
            .background(colors.background.ignoresSafeArea())
            .sheet(isPresented: overlayPresented) { overlayContent }
    }

    // MARK: Layout

    private var splitLayout: some View {
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

    /// The window's native title-bar actions (macOS): the sidebar toggle, the drilled ← back and the account
    /// switcher sit leading; Search then the shell-computed `ChromeSpec.actions` trail to the top-right.
    /// This is the desktop counterpart of the Android shell chrome's trailing glyph row.
    ///
    /// Every button carries BOTH a `.help` tooltip and an explicit `.accessibilityLabel` (#368 G23): an
    /// icon-only title-bar button genuinely needs the tooltip, but `.help` alone lands as a VoiceOver
    /// *hint*, which left these buttons unnamed.
    @ToolbarContentBuilder
    private var windowToolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            Button {
                withAnimation { columnVisibility = columnVisibility == .detailOnly ? .all : .detailOnly }
            } label: {
                Image(systemName: "sidebar.leading")
            }
            .help(L.string("shell_toggle_sidebar"))
            .accessibilityLabel(L.string("shell_toggle_sidebar"))
        }
        // Drilled into a tier-3 detail (Plan task / Settings category): a ← back that pops via the shell's
        // onBack, mirroring the iOS/Android chrome's leading affordance.
        if drilled {
            ToolbarItem(placement: .navigation) {
                Button { _ = component.onBack() } label: { Image(systemName: "chevron.backward") }
                    .help(L.string("common_back"))
                    .accessibilityLabel(L.string("common_back"))
            }
        }
        if accounts.accounts.count > 1 {
            ToolbarItem(placement: .navigation) { accountSwitcher }
        }
        ToolbarItemGroup(placement: .primaryAction) {
            // Search is NOT a ChromeActionKind (the shared catalog is Refresh / BrainDump / New), so it
            // stays the shell's own button, leading the group exactly as it always has.
            Button { ShellBridgeKt.openSearchOverlay(component: component) } label: {
                Image(systemName: "magnifyingglass")
            }
            .help(L.string("common_search"))
            .accessibilityLabel(L.string("common_search"))

            // The shell-computed actions (#368 G21) — the twin of iOS's `ChromeToolbar`: kind → glyph +
            // handler, in spec order. This replaced a hardcoded pair behind a hand-rolled `if !drilled`,
            // which meant any *new* ChromeActionKind would silently never appear on macOS, and which lost
            // Plan's Refresh action outright. `drilled` needs no hand-check now: a drilled chrome carries
            // no actions at all (`DefaultMainShellComponent.drilledChrome`).
            ForEach(0..<Int(ShellBridgeKt.chromeActionCount(spec: chrome.value)), id: \.self) { i in
                let index = Int32(i)
                let kind = ShellBridgeKt.chromeActionKind(spec: chrome.value, index: index)
                if let glyph = Self.actionGlyph(kind) {
                    Button {
                        // Brain dump is the ONE kind macOS does not route through the shared handler:
                        // ChromeActionKind.BrainDump opens `OverlayRoute.BrainDump`, and macOS has neither a
                        // BrainDumpView nor an `overlayBrainDump` bridge accessor (Tranche 5 of #368) — so
                        // invoking it would occupy the shared overlay slot with nothing on screen and no way
                        // to reach `dismissOverlay()`. macOS's Brain dump is instead the on-device
                        // `DraftExtractorView` sheet that `onBrainDump` presents (the same one as ⌘⇧E).
                        if kind == "BrainDump" { onBrainDump() }
                        else { ShellBridgeKt.chromeInvoke(spec: chrome.value, index: index) }
                    } label: {
                        Image(systemName: glyph)
                    }
                    .help(Self.actionLabel(kind))
                    .accessibilityLabel(Self.actionLabel(kind))
                }
            }

            // DELIBERATE macOS carve-out — do not "fix" this by editing the shared spec. The shell drops the
            // capture pair while the Tasks tree is in move mode (`rootChrome(…, capture = it.moveMode ==
            // null)`) because on a PHONE the FAB pair sits bottom-centre, directly on top of the modal move
            // bar. A title-bar button covers nothing, so macOS re-adds the pair whenever a Destination root's
            // spec omitted it. The suppression is computed in commonMain, so Swift cannot simply "ignore"
            // it — the actions never arrive, and this union is the only way to render them. `onNewTapped`
            // reproduces the spec's New handler, Calendar pre-dating (#74) included.
            if !drilled && !specHasCapture {
                Button(action: onBrainDump) { Image(systemName: "brain.head.profile") }
                    .help(L.string("braindump_title"))
                    .accessibilityLabel(L.string("braindump_title"))
                Button(action: onNewTapped) { Image(systemName: "plus") }
                    .help(L.string("shell_drawer_new_task"))
                    .accessibilityLabel(L.string("shell_drawer_new_task"))
            }
        }
    }

    /// The SF Symbol for a `ChromeActionKind` (mirrors `ShellChrome`'s glyph switch). Brain dump keeps the
    /// macOS `brain.head.profile` — the on-device Extractor — NOT iOS's `waveform`, which stands for a
    /// recorder macOS does not have. `nil` means "no macOS surface", so the button is simply not drawn.
    private static func actionGlyph(_ kind: String) -> String? {
        switch kind {
        case "Refresh": return "arrow.clockwise"
        case "New": return "plus"
        case "BrainDump": return "brain.head.profile"
        default: return nil
        }
    }

    private static func actionLabel(_ kind: String) -> String {
        switch kind {
        case "Refresh": return L.string("common_refresh")
        case "BrainDump": return L.string("braindump_title")
        default: return L.string("shell_drawer_new_task")
        }
    }

    // MARK: Active Destination body

    private var destinationBody: some View {
        let child = active
        return Group {
            if let plan = ShellBridgeKt.destPlan(child: child) {
                // The dashboard's "See everything ›" link routes to the whole forest, which `PlanComponent`
                // has no intent for — the Tasks Destination *is* the forest, so only the shell can honour it
                // (#368 G16). iOS leaves the link inert for exactly this reason; macOS hands it down.
                PlanHostView(plan: plan) { component.selectDestination(destination: Destination.tasks) }
            } else if let calendar = ShellBridgeKt.destCalendar(child: child) {
                CalendarView(component: calendar)
            } else if let tasks = ShellBridgeKt.destTasks(child: child) {
                TasksScreen(root: ShellBridgeKt.tasksRoot(component: tasks))
            } else if let assistant = ShellBridgeKt.destAssistant(child: child) {
                AssistantView(component: assistant)
            } else if let activity = ShellBridgeKt.destActivity(child: child) {
                // The cross-surface action ledger (#260) — real since the shell started building a live
                // ActivityComponent; before #368 this Destination dead-ended on the Coming soon body below
                // even though a populated feed was sitting behind it.
                ActivityView(component: activity)
            } else if let profile = ShellBridgeKt.destProfile(child: child) {
                ProfileView(component: profile)
            } else if let settings = ShellBridgeKt.destSettings(child: child) {
                SettingsView(component: settings)
            } else {
                // Inbox is the last Destination without a macOS surface (brain-dump capture, Tranche 5).
                EmptyStateView(title: L.format("shell_coming_soon_title", activeName), message: L.string("shell_coming_soon_body_brief"))
            }
        }
        // The Active Account's session has expired (#297): every read surface banners it. Profile is
        // excluded — it shows the same prompt inside its own card.
        .safeAreaInset(edge: .top, spacing: 0) {
            if sessionExpired.value.boolValue && ShellBridgeKt.destProfile(child: child) == nil {
                SessionExpiredBanner { ShellBridgeKt.shellSignInAgain(component: component) }
            }
        }
    }

    // MARK: Shell chrome

    private var accountSwitcher: some View {
        Menu {
            ForEach(accounts.accounts) { account in
                Button(account.label) { ShellBridgeKt.switchToAccount(component: component, account: account) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(accounts.active?.label ?? L.string("shell_select_account"))
                    .font(.subheadline.weight(.medium))
                Image(systemName: "chevron.down").font(.caption2)
            }
            .foregroundStyle(colors.onSurface)
        }
        .accessibilityLabel(L.string("shell_switch_account_cd"))
    }

    /// The toolbar's New handler — also the carve-out's stand-in for the spec's own New action.
    private func onNewTapped() {
        // On Calendar New pre-dates to the selected day (#74); elsewhere it opens an undated form.
        if let calendar = ShellBridgeKt.destCalendar(child: active) {
            calendar.onNewForSelectedDay()
        } else {
            ShellBridgeKt.openNewOverlay(component: component)
        }
    }

    // MARK: Sidebar

    private var sidebar: some View {
        // Always-labelled rows. The min column width fits a full label so they never truncate; collapse
        // the whole sidebar (the toolbar toggle) when you want it out of the way, rather than an icon rail.
        List {
            ForEach(allDestinations) { dest in
                let name = ShellBridgeKt.destinationName(destination: dest)
                let selected = name == activeRawName
                Button { component.selectDestination(destination: dest) } label: {
                    Label(L.destinationLabel(name), systemImage: icon(name))
                        .foregroundStyle(selected ? colors.primary : colors.onSurface)
                }
                // Which row is current was conveyed by colour alone (#368 G23). The trait says it out loud.
                // There is deliberately no `.help` here: it would only repeat the row's own visible label,
                // and macOS reads `.help` as the VoiceOver hint — so the name was announced twice.
                .accessibilityAddTraits(selected ? [.isSelected] : [])
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

    /// The sidebar glyph per Destination. Deliberately a little bolder than `DefernoIcon`'s set (`house.fill`
    /// vs `.home`'s `house`) — a sidebar row wants weight. Inbox and Activity were falling through to the
    /// `circle` backstop, drawing two anonymous dots (#368); they take exactly the symbols
    /// `DefernoIcon.inbox` / `.activity` declare, so the two sources agree.
    private func icon(_ name: String) -> String {
        switch name {
        case "Plan": return "house.fill"
        case "Calendar": return "calendar"
        case "Tasks": return "list.bullet"
        case "Assistant": return "sparkles"
        case "Inbox": return "tray"
        case "Activity": return "bell"
        case "Profile": return "person.fill"
        case "Settings": return "gearshape.fill"
        default: return "circle"
        }
    }
}
