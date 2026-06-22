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
    @StateObject private var chrome: StateFlowObserver<ChromeSpec>
    /// The live count of Ready brain-dump drafts — the Inbox drawer-row badge (shell-level, so it shows
    /// even before the Inbox Destination is first visited).
    @StateObject private var inboxBadge: StateFlowObserver<KotlinInt>
    @State private var drawerOpen = false
    /// Non-nil only while a finger is dragging the open content back toward the edge — makes the drawer
    /// track the finger 1:1. The effective open fraction is `dragX != nil ? base + dragX/width : base`.
    @State private var dragX: CGFloat? = nil

    init(component: MainShellComponent) {
        self.component = component
        _destinations = StateObject(wrappedValue: DestinationStackObserver(ShellBridgeKt.destinationStackBridge(component: component)))
        _overlay = StateObject(wrappedValue: OverlaySlotObserver(ShellBridgeKt.overlaySlotBridge(component: component)))
        _accounts = StateObject(wrappedValue: AccountsObserver(ShellBridgeKt.accountSwitcherBridge(component: component)))
        _chrome = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.chromeBridge(component: component)))
        _inboxBadge = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.inboxReadyCountBridge(component: component)))
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
                    // The slid-aside content is the dismiss target: tap or drag-left to close. Sized to the
                    // content (NOT ignoresSafeArea, which would make the Color fill the whole screen and
                    // swallow taps meant for the drawer underneath) and laid over the card *before* the
                    // offset, so it rides with the card. allowsHitTesting only once fully open, so an opening
                    // edge-drag (fraction rising while still "closed") passes straight through to that drag.
                    .overlay {
                        if fraction > 0 {
                            // Transparent — the revealed card is NOT dimmed; this layer exists only as the
                            // dismiss target (contentShape makes Color.clear hit-testable).
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture { setDrawer(false) }
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

    // MARK: Content card (top bar + active Destination), drawn on top of the drawer

    private var content: some View {
        VStack(spacing: 0) {
            shellTopBar
            destinationBody
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(colors.background)
    }

    /// The one adaptive top bar (Cand 1), the SwiftUI twin of Android's `ShellTopBar`: a leading ☰ menu at
    /// a Destination root (opens the drawer) or ← back when drilled into a tier-3 detail (pops via the
    /// shell's `onBack`), the shell-computed title, and the trailing create actions by kind. Brain dump is
    /// skipped on iOS — there's no iOS inference engine / overlay yet (DefernoRoot.kt, ADR-0027/#150).
    private var shellTopBar: some View {
        let spec = chrome.value
        let drilled = ShellBridgeKt.chromeDrilled(spec: spec)
        return HStack(spacing: 4) {
            if drilled {
                Button { _ = component.onBack() } label: { Image(systemName: "chevron.backward") }
                    .accessibilityLabel("Back")
            } else {
                Button { setDrawer(true) } label: { Image(systemName: "line.3.horizontal") }
                    .accessibilityLabel("Menu")
            }
            Text(ShellBridgeKt.chromeTitle(spec: spec))
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)
            ForEach(0..<Int(ShellBridgeKt.chromeActionCount(spec: spec)), id: \.self) { i in
                let index = Int32(i)
                let kind = ShellBridgeKt.chromeActionKind(spec: spec, index: index)
                if let glyph = actionGlyph(kind) {
                    Button { ShellBridgeKt.chromeInvoke(spec: spec, index: index) } label: {
                        Image(systemName: glyph)
                    }
                    .accessibilityLabel(kind == "BrainDump" ? "Brain dump" : kind)
                }
            }
        }
        .font(.title3)
        .foregroundStyle(colors.onSurface)
        .padding(.horizontal, 8)
        .frame(minHeight: 52)
        .background(colors.background)
    }

    /// The SF Symbol for a `ChromeActionKind` (mirrors `ShellChrome`'s glyph switch). Brain dump now opens
    /// the iOS recorder overlay (DefernoRoot wires the native record seam, ADR-0027).
    private func actionGlyph(_ kind: String) -> String? {
        switch kind {
        case "Refresh": return "arrow.clockwise"
        case "New": return "plus"
        case "BrainDump": return "waveform"
        default: return nil
        }
    }

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
        } else if let inbox = ShellBridgeKt.destInbox(child: child) {
            InboxView(component: inbox)
        } else if let activity = ShellBridgeKt.destActivity(child: child) {
            ActivityView(component: activity)
        } else {
            EmptyStateView(title: "\(activeName) is coming soon", message: "This area is on the way.")
        }
    }

    // MARK: Reveal drawer (Account header → Search → Destinations), drawn underneath the content

    private var drawer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                // Brand wordmark (mirrors Android's ShellDrawer header).
                HStack(spacing: 8) {
                    Brandmark(height: 24)
                    Text("Deferno").font(.title3.weight(.bold)).foregroundStyle(colors.onSurface)
                }
                .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 4)

                // "Add something" — the two capture affordances (parity with Android's drawer).
                SectionLabel("Add something").padding(.horizontal, 16).padding(.top, 8)
                PrimaryActionButton(title: "New task", subtitle: "Fill in the details", icon: .plus) {
                    setDrawer(false)
                    ShellBridgeKt.openNewOverlay(component: component)
                }
                .padding(.horizontal, 16).padding(.top, 4)
                TonalActionButton(title: "Brain dump", subtitle: "Speak or type — sorts to Inbox", icon: .waveform) {
                    setDrawer(false)
                    ShellBridgeKt.openBrainDumpOverlay(component: component)
                }
                .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 8)

                drawerRow(label: "Search", system: "magnifyingglass", selected: false) {
                    setDrawer(false)
                    ShellBridgeKt.openSearchOverlay(component: component)
                }
                ForEach(component.destinations) { dest in
                    let name = ShellBridgeKt.destinationName(destination: dest)
                    let badge: String? = name == "Inbox"
                        ? (inboxBadge.value.intValue > 0 ? "\(inboxBadge.value.intValue)" : "empty")
                        : nil
                    drawerRow(label: name, system: icon(name), selected: name == activeName, badge: badge) {
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
        DragGesture(minimumDistance: 10)
            .onChanged { dragX = $0.translation.width }
            .onEnded { value in
                let predicted = (drawerOpen ? 1 : 0) + value.predictedEndTranslation.width / drawerWidth
                withAnimation(Self.drawerAnimation) {
                    drawerOpen = predicted >= 0.5
                    dragX = nil
                }
            }
    }

    // MARK: Overlay (Search / New / Help→Feedback) as a sheet

    // A Plan-tapped Task is no longer a shell overlay (#51): it drills into the Plan Destination's own
    // tier-3 stack (PlanHostView), rendered inside the chrome so the drawer stays live.

    private var overlaySearchComponent: SearchComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlaySearch(child: $0) }
    }

    private var overlayNewComponent: NewComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlayNew(child: $0) }
    }

    private var overlayFeedbackComponent: FeedbackComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlayFeedback(child: $0) }
    }

    private var overlayBrainDumpComponent: BrainDumpComponent? {
        overlay.current.flatMap { ShellBridgeKt.overlayBrainDump(child: $0) }
    }

    private var overlayPresented: Binding<Bool> {
        Binding(
            get: { overlaySearchComponent != nil || overlayNewComponent != nil
                || overlayFeedbackComponent != nil || overlayBrainDumpComponent != nil },
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
            BrainDumpView(component: brainDump)
        }
    }

    // MARK: Destination glyphs (a View concern, mirrors ShellChrome's Destination.icon)

    private func icon(_ name: String) -> String {
        switch name {
        case "Plan": return "house.fill"
        case "Calendar": return "calendar"
        case "Tasks": return "list.bullet"
        case "Inbox": return "tray"
        case "Activity": return "bell"
        case "Profile": return "person.fill"
        case "Settings": return "gearshape.fill"
        default: return "circle"
        }
    }
}
