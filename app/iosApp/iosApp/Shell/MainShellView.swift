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
    /// The shared Brain dump recorder (#267) — forwarded to the overlay's spectrum.
    let recorder: BrainDumpRecorder
    @Environment(\.defernoColors) private var colors
    @StateObject private var destinations: StateFlowObserver<MainShellComponentDestinationChild>
    /// The dynamic nav registry — the conditionally-present Assistant row (ADR-0040) appears once the Org
    /// resolves to `entitled`, so the drawer observes it reactively rather than reading a fixed array.
    @StateObject private var navDestinations: DestinationsObserver
    @StateObject private var overlay: OptionalStateFlowObserver<MainShellComponentOverlayChild>
    @StateObject private var accounts: AccountsObserver
    @StateObject private var chrome: StateFlowObserver<ChromeSpec>
    /// The live count of Ready brain-dump drafts — the Inbox drawer-row badge (shell-level, so it shows
    /// even before the Inbox Destination is first visited).
    @StateObject private var inboxBadge: StateFlowObserver<KotlinInt>
    /// The Active Account's session-expired flag (#297) — drives the read-surface "Session expired" banner.
    @StateObject private var sessionExpired: StateFlowObserver<KotlinBoolean>
    @State private var drawerOpen = false
    /// Non-nil only while a finger is dragging the open content back toward the edge — makes the drawer
    /// track the finger 1:1. The effective open fraction is `dragX != nil ? base + dragX/width : base`.
    @State private var dragX: CGFloat? = nil

    init(component: MainShellComponent, recorder: BrainDumpRecorder) {
        self.component = component
        self.recorder = recorder
        _destinations = StateObject(wrappedValue: StateFlowObserver(component.activeDestination))
        _navDestinations = StateObject(wrappedValue: DestinationsObserver(component.destinations))
        _overlay = StateObject(wrappedValue: OptionalStateFlowObserver(component.activeOverlay))
        _accounts = StateObject(wrappedValue: AccountsObserver(accounts: component.accounts, active: component.activeAccount))
        _chrome = StateObject(wrappedValue: StateFlowObserver(component.chrome))
        _inboxBadge = StateObject(wrappedValue: StateFlowObserver(component.inboxReadyCount))
        _sessionExpired = StateObject(wrappedValue: StateFlowObserver(component.sessionExpired))
    }

    private var active: MainShellComponentDestinationChild { destinations.value }
    private var activeName: String { ShellBridgeKt.destinationName(destination: ShellBridgeKt.destinationOf(child: active)) }

    var body: some View {
        GeometryReader { geo in
            // Wide enough to read as a drawer but always leaving a peek of content; capped so a big iPad
            // window doesn't get an absurdly wide menu (mirrors ShellChrome's 0.82f / 320dp).
            let drawerWidth = min(geo.size.width * 0.82, 320)
            let fraction = openFraction(drawerWidth)
            // VoiceOver has no z-order, so gate each overlaid layer on the drawer state: the closed
            // drawer's off-screen nav rows are hidden, and the covered content is hidden while it's open
            // (#358). Exactly one layer is reachable per state — see ShellAccessibility.
            let a11y = ShellAccessibility(drawerOpen: drawerOpen)
            ZStack(alignment: .leading) {
                drawer
                    .frame(width: drawerWidth)
                    .frame(maxHeight: .infinity)
                    .accessibilityHidden(a11y.drawerHidden)
                content
                    .frame(width: geo.size.width, height: geo.size.height)
                    .accessibilityHidden(a11y.contentHidden)
                    .shadow(color: .black.opacity(0.18 * fraction), radius: 8, x: -2)
                    // The slid-aside content is the dismiss target: tap or drag-left to close. Sized to the
                    // content (NOT ignoresSafeArea, which would make the Color fill the whole screen and
                    // swallow taps meant for the drawer underneath) and laid over the card *before* the
                    // offset, so it rides with the card. allowsHitTesting only once fully open, so an opening
                    // edge-drag (fraction rising while still "closed") passes straight through to that drag.
                    .overlay {
                        if fraction > 0 {
                            // Transparent — the revealed card is NOT dimmed; this layer exists only as the
                            // dismiss target (contentShape makes Color.clear hit-testable). One recognizer
                            // only: a tap-to-dismiss is folded into the drag's release (a near-stationary
                            // gesture), so there's no tap-vs-drag arbitration to stall the 0-distance drag.
                            Color.clear
                                .contentShape(Rectangle())
                                .gesture(drawerDrag(drawerWidth))
                                .allowsHitTesting(drawerOpen)
                        }
                    }
                    .offset(x: fraction * drawerWidth)
                // Left-edge swipe-to-open handle: a freeform inward swipe opens the drawer, tracking the
                // finger (mirrors ShellChrome's edge handle). Inset below the top bar so it never eats the
                // hamburger tap. Present only while closed; the content (above) handles closing.
                if !drawerOpen {
                    Color.clear
                        .contentShape(Rectangle())
                        .gesture(drawerDrag(drawerWidth))
                        .frame(width: 24)
                        .frame(maxHeight: .infinity)
                        .padding(.top, 56)
                }
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

    // MARK: Content card (native nav bar + active Destination), drawn on top of the drawer

    /// The active Destination under a **native** nav bar (#263). Every non-Tasks Destination is wrapped in
    /// a shell-owned `NavigationStack` whose `.toolbar` renders the shared `ChromeSpec` (the SwiftUI twin of
    /// Android's `ShellTopBar`: a leading ☰ menu at a Destination root / ← back when drilled into a tier-3
    /// detail, the shell-computed title, and the trailing create actions). Tasks owns its **own** stack +
    /// bar so its `.searchable` collapse-on-scroll field can live on the tree (see `TasksScreen`); the shell
    /// just threads the chrome spec + menu open down. All real navigation is Decompose — the shell's stack
    /// never pushes (its path stays empty); it is a chrome surface driven by `chrome`.
    private var content: some View {
        let child = active
        return Group {
            if let tasks = ShellBridgeKt.destTasks(child: child) {
                TasksScreen(
                    root: ShellBridgeKt.tasksRoot(component: tasks),
                    onAdd: { ShellBridgeKt.openNewOverlay(component: component) },
                    onMenu: { setDrawer(true) },
                    chromeSpec: chrome.value
                )
            } else if let assistant = ShellBridgeKt.destAssistant(child: child) {
                // The Assistant owns its own native chat chrome (ADR-0040 iOS carve-out, like Tasks): its
                // own nav bar (☰ + conversation switcher + new chat), not the shared ChromeToolbar.
                AssistantView(component: assistant, onMenu: { setDrawer(true) })
            } else {
                NavigationStack {
                    destinationBody
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .navigationTitle(L.chromeTitle(chrome.value))
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ChromeToolbar(
                                spec: chrome.value,
                                onMenu: { setDrawer(true) },
                                onBack: { _ = component.onBack() }
                            )
                        }
                }
            }
        }
        .background(colors.background)
        // The Active Account's session has expired (#297): every in-chrome read surface banners it (a top
        // safe-area inset, so it rides below the status bar). Profile is excluded — it shows the same prompt
        // inside its own card. Tasks (its own stack, above) gets the banner here too.
        .safeAreaInset(edge: .top, spacing: 0) {
            if sessionExpired.value.boolValue && ShellBridgeKt.destProfile(child: child) == nil {
                SessionExpiredBanner { ShellBridgeKt.shellSignInAgain(component: component) }
            }
        }
    }

    @ViewBuilder
    private var destinationBody: some View {
        let child = active
        if let plan = ShellBridgeKt.destPlan(child: child) {
            PlanHostView(plan: plan)
        } else if let calendar = ShellBridgeKt.destCalendar(child: child) {
            CalendarView(component: calendar)
        } else if let profile = ShellBridgeKt.destProfile(child: child) {
            ProfileView(component: profile)
        } else if let settings = ShellBridgeKt.destSettings(child: child) {
            SettingsView(component: settings)
        } else if let inbox = ShellBridgeKt.destInbox(child: child) {
            InboxView(component: inbox)
        } else if let activity = ShellBridgeKt.destActivity(child: child) {
            ActivityView(component: activity)
        } else {
            EmptyStateView(title: L.format("shell_coming_soon_title", L.destinationLabel(activeName)), message: L.string("shell_coming_soon_body_brief"))
        }
    }

    // MARK: Reveal drawer (Account header → Search → Destinations), drawn underneath the content

    private var drawer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                // Brand wordmark (mirrors Android's ShellDrawer header).
                HStack(spacing: 8) {
                    Brandmark(height: 24)
                    Text(L.string("common_app_name")).font(.title3.weight(.bold)).foregroundStyle(colors.onSurface)
                }
                .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 4)

                // "Add something" — the two capture affordances (parity with Android's drawer).
                SectionLabel(L.string("shell_drawer_add_something")).padding(.horizontal, 16).padding(.top, 8)
                PrimaryActionButton(title: L.string("shell_drawer_new_task"), subtitle: L.string("shell_drawer_new_task_subtitle"), icon: .plus) {
                    setDrawer(false)
                    ShellBridgeKt.openNewOverlay(component: component)
                }
                .padding(.horizontal, 16).padding(.top, 4)
                TonalActionButton(title: L.string("braindump_title"), subtitle: L.string("shell_drawer_brain_dump_subtitle"), icon: .waveform) {
                    setDrawer(false)
                    ShellBridgeKt.openBrainDumpOverlay(component: component)
                }
                .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 8)

                drawerRow(label: L.string("common_search"), system: "magnifyingglass", selected: false) {
                    setDrawer(false)
                    ShellBridgeKt.openSearchOverlay(component: component)
                }
                ForEach(navDestinations.destinations) { dest in
                    let name = ShellBridgeKt.destinationName(destination: dest)
                    let badge: String? = name == "Inbox"
                        ? (inboxBadge.value.intValue > 0 ? "\(inboxBadge.value.intValue)" : L.string("shell_inbox_badge_empty"))
                        : nil
                    drawerRow(label: L.destinationLabel(name), system: icon(name), selected: name == activeName, badge: badge) {
                        setDrawer(false)
                        component.selectDestination(destination: dest)
                    }
                }

                Divider().background(colors.outlineVariant).padding(.horizontal, 16).padding(.vertical, 8)
                accountHeader
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
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
                    Text(accounts.active?.label ?? L.string("shell_select_account")).font(.headline)
                    Image(systemName: "chevron.down").font(.caption2)
                }
                .foregroundStyle(colors.onSurface)
            }
            .accessibilityLabel(L.string("shell_switch_account_cd"))
        } else {
            Text(accounts.active?.label ?? L.string("common_app_name"))
                .font(.headline)
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func drawerRow(label: String, system: String, selected: Bool, badge: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: system).font(.system(size: 20)).frame(width: 24)
                Text(label).font(.body)
                Spacer()
                if let badge {
                    Text(badge)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(colors.inkMuted)
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(colors.surface, in: Capsule())
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: Layout.minTouchTarget)
            .foregroundStyle(selected ? colors.primary : colors.onSurface)
            .background(selected ? colors.primaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: 24))
            .contentShape(Rectangle())
        }
        .padding(.horizontal, 12)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(badge != nil ? "\(label), \(badge!)" : label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Drawer open/close

    private static let drawerAnimation = Animation.spring(response: 0.32, dampingFraction: 0.86)

    private func setDrawer(_ open: Bool) {
        withAnimation(Self.drawerAnimation) { drawerOpen = open }
    }

    /// The finger-tracking drawer drag, shared by the closed-state edge handle (opens) and the open scrim
    /// (closes): track the finger 1:1 via `dragX`, then on release settle to the nearer end — folding the
    /// fling velocity in via `predictedEndTranslation`, so a fast flick commits even from a short drag
    /// (momentum). `dragX` is raw translation; `openFraction` clamps it against the current open base, so
    /// the one gesture serves both directions (open from 0, close from 1).
    private func drawerDrag(_ drawerWidth: CGFloat) -> some Gesture {
        // Global coordinate space, not the default .local: the close-drag overlay rides *inside* the
        // `.offset`-ed content, so a local-space translation would be re-measured in a frame the offset
        // itself moves each tick — a feedback loop that jitters the card ~40px every other frame. Global
        // space is stationary, breaking the loop. (Opening is unaffected: its edge handle never moves.)
        DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .onChanged { dragX = $0.translation.width }
            .onEnded { value in
                // A near-stationary press is a tap, not a drag: on the open scrim it dismisses, on the
                // closed edge handle it's a no-op (stays closed). A real drag settles to the nearer end,
                // folding fling velocity in via predictedEndTranslation so a short flick still commits.
                let moved = abs(value.translation.width) + abs(value.translation.height)
                let predicted = (drawerOpen ? 1 : 0) + value.predictedEndTranslation.width / drawerWidth
                withAnimation(Self.drawerAnimation) {
                    drawerOpen = moved < 10 ? false : predicted >= 0.5
                    dragX = nil
                }
            }
    }

    // MARK: Overlay (Search / New / Help→Feedback) as a sheet

    // A Plan-tapped Task is no longer a shell overlay (#51): it drills into the Plan Destination's own
    // tier-3 stack (PlanHostView), rendered inside the chrome so the drawer stays live.

    private var overlaySearchComponent: SearchComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlaySearch(child: $0) }
    }

    private var overlayNewComponent: NewComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlayNew(child: $0) }
    }

    private var overlayFeedbackComponent: FeedbackComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlayFeedback(child: $0) }
    }

    private var overlayBrainDumpComponent: BrainDumpComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlayBrainDump(child: $0) }
    }

    private var overlayBreakdownComponent: BreakdownComponent? {
        overlay.value.flatMap { ShellBridgeKt.overlayBreakdown(child: $0) }
    }

    private var overlayPresented: Binding<Bool> {
        Binding(
            get: { overlaySearchComponent != nil || overlayNewComponent != nil
                || overlayFeedbackComponent != nil || overlayBrainDumpComponent != nil
                || overlayBreakdownComponent != nil },
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
            // Settings → Help & Feedback opens the in-app feedback form here (#375).
            FeedbackView(component: feedback)
        } else if let brainDump = overlayBrainDumpComponent {
            // The shell top-bar voice action opens the iOS Brain dump recorder (ADR-0027).
            BrainDumpView(component: brainDump, recorder: recorder)
        } else if let breakdown = overlayBreakdownComponent {
            // Item detail's "Break this down" opens the on-device impediment flow (Deferno#525).
            BreakdownView(component: breakdown)
        }
    }

    // MARK: Destination glyphs (a View concern, mirrors ShellChrome's Destination.icon)

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

/// The native nav-bar twin of the old hand-drawn shell bar (#263): renders the Compose-free `ChromeSpec`
/// as SwiftUI `.toolbar` items — a leading ☰ menu (Destination root) or ← back (drilled tier-3), and the
/// trailing create actions by kind. Shared so the chrome is identical app-wide: the shell's NavigationStack
/// uses it for every non-Tasks Destination, and `TasksScreen`'s own stack uses it for the tree. Actions
/// carry their own handler (`chromeInvoke`); only the shell-level menu/back are threaded in as closures.
struct ChromeToolbar: ToolbarContent {
    let spec: ChromeSpec
    var onMenu: () -> Void = {}
    var onBack: () -> Void = {}

    var body: some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            if ShellBridgeKt.chromeDrilled(spec: spec) {
                Button { onBack() } label: { Image(systemName: "chevron.backward") }
                    .accessibilityLabel(L.string("common_back"))
            } else {
                Button { onMenu() } label: { Image(systemName: "line.3.horizontal") }
                    .accessibilityLabel(L.string("shell_menu_cd"))
            }
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            ForEach(0..<Int(ShellBridgeKt.chromeActionCount(spec: spec)), id: \.self) { i in
                let index = Int32(i)
                let kind = ShellBridgeKt.chromeActionKind(spec: spec, index: index)
                if let glyph = Self.actionGlyph(kind) {
                    Button { ShellBridgeKt.chromeInvoke(spec: spec, index: index) } label: {
                        Image(systemName: glyph)
                    }
                    .accessibilityLabel(kind == "BrainDump" ? L.string("braindump_title") : kind == "Refresh" ? L.string("common_refresh") : L.string("shell_drawer_new_task"))
                }
            }
        }
    }

    /// The SF Symbol for a `ChromeActionKind` (mirrors `ShellChrome`'s glyph switch). Brain dump opens the
    /// iOS recorder overlay (DefernoRoot wires the native record seam, ADR-0027).
    private static func actionGlyph(_ kind: String) -> String? {
        switch kind {
        case "Refresh": return "arrow.clockwise"
        case "New": return "plus"
        case "BrainDump": return "waveform"
        default: return nil
        }
    }
}
