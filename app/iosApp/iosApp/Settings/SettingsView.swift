import Deferno
import SwiftUI
import UserNotifications
import WebKit

/// The Settings Destination (#72) — a **tier-3 drill-down** (`SettingsChild`: List ↔ Detail(category))
/// rendered from the shared component's stack, so the single adaptive shell bar (`MainShellView`) titles
/// it ("Settings" / the category) and drives ← back. A thin renderer of `SettingsComponent`: backed
/// categories read/write the Active Account's `UserSettings` (Appearance applies the theme live), the
/// unbacked ones (Security & 2FA, Integrations) are gentle coming-soon stubs, and host concerns
/// (export/import, feedback, app permissions, console) are forwarded for the shell to deep-link. The
/// SpeechEngine + Agent rows stay hidden until a real device engine is registered (#95/#150). Mirrors
/// macOS's `SettingsView`.
struct SettingsView: View {
    let component: SettingsComponent
    @StateObject private var stack: StateFlowObserver<SettingsComponentSettingsChild>
    @StateObject private var settings: StateFlowObserver<UserSettings>
    @StateObject private var speech: StateFlowObserver<SpeechEngineSettings>
    @StateObject private var inference: StateFlowObserver<InferenceEngineSettings>
    // The server-mediated Assistant enablement (#282, ADR-0040): the Owner's persistent disable /
    // withdraw-consent row, shown only when the Org is entitled (iOS-only in v1).
    @StateObject private var assistant: StateFlowObserver<AssistantSettings>
    @Environment(\.defernoColors) private var colors
    @Environment(\.openURL) private var openURL
    // The "Brain dump notifications" opt-in (#271): device-local, seeded once from the AppScope preference;
    // the toggle persists through the component and requests OS authorization on enable.
    @State private var brainDumpNotifications = false
    @State private var brainDumpNotificationsSeeded = false
    // The legal page presented in-app via a WKWebView that hides the site nav/footer (nil = none).
    // Compliant in-app presentation of our hosted Terms/Privacy — no link-stripping needed (Apple
    // 3.1.1 is about external purchase flows, not legal text).
    @State private var legalPage: LegalPage?

    init(component: SettingsComponent) {
        self.component = component
        _stack = StateObject(wrappedValue: StateFlowObserver(component.activeChild))
        _settings = StateObject(wrappedValue: StateFlowObserver(component.settings))
        _speech = StateObject(wrappedValue: StateFlowObserver(component.speechEngine))
        _inference = StateObject(wrappedValue: StateFlowObserver(component.inferenceEngine))
        _assistant = StateObject(wrappedValue: StateFlowObserver(component.assistant))
    }

    // Stack-driven (the component's List↔Detail stack, mirroring macOS) so the single adaptive shell bar
    // (MainShellView) reflects the foreground surface — "Settings" at the root, the category title + ←
    // back when drilled. Drilling is `openCategory`; back routes through the shell's `onBack`.
    var body: some View {
        Group {
            if let category = ShellBridgeKt.settingsChildCategory(child: stack.value) {
                detail(category)
                    .scrollContentBackground(.hidden)
            } else {
                categoryList
            }
        }
        .background(colors.background)
    }

    private var categoryList: some View {
        List {
            Section {
                ForEach(categories) { category in
                    Button {
                        component.openCategory(category: category)
                    } label: {
                        HStack {
                            Text(title(category)).foregroundStyle(colors.onSurface)
                            Spacer()
                            if !ShellBridgeKt.settingsCategoryBacked(category: category) {
                                Text("Coming soon").font(.subheadline).foregroundStyle(colors.inkMuted)
                            }
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
                        }
                    }
                    .listRowBackground(colors.surfaceCard)
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    /// Hide the device-local engine rows until one is registered (matches Android): SpeechEngine until an
    /// iOS speech engine ships (#95), Agent until an inference engine is available (#150) — else the row
    /// would open an empty "coming soon".
    private var categories: [SettingsCategory] {
        ShellBridgeKt.settingsCategories().filter { category in
            switch ShellBridgeKt.settingsCategoryName(category: category) {
            case "SpeechEngine": return speech.value.available
            case "Agent": return inference.value.available
            // The Assistant row shows only once the Org is entitled (ADR-0040); hidden otherwise (the
            // inert seam on a non-entitled account / non-iOS host yields not-available).
            case "Assistant": return assistant.value.available
            default: return true
            }
        }
    }

    // MARK: Detail per category (the shell bar supplies the title + ← back)

    @ViewBuilder
    private func detail(_ category: SettingsCategory) -> some View {
        switch ShellBridgeKt.settingsCategoryName(category: category) {
        case "Appearance": appearanceDetail
        case "TaskBehavior": taskBehaviorDetail
        case "SpeechEngine": speechDetail
        case "Agent": agentDetail
        case "Assistant": assistantDetail
        case "DataPrivacy": dataPrivacyDetail
        case "HelpFeedback": linkDetail(text: "Tell us what's working and what isn't.", action: "Send feedback") { component.onOpenSubmitFeedback() }
        case "AppPermissions": linkDetail(text: "Manage microphone and notification access in iOS Settings.", action: "Open app settings") { component.onOpenAppPermissions() }
        case "Legal": legalDetail
        case "Account": accountDetail
        case "Security2FA": comingSoon(action: "Open security console") { component.onOpenConsole() }
        default: comingSoon(action: nil, perform: nil)
        }
    }

    private var appearanceDetail: some View {
        let value = settings.value
        return List {
            Section("Theme") {
                checkRow("Deferno", selected: value.themeFamily == ThemeFamily.deferno) { component.onThemeFamilyChanged(family: ThemeFamily.deferno) }
                checkRow("Mono", selected: value.themeFamily == ThemeFamily.mono) { component.onThemeFamilyChanged(family: ThemeFamily.mono) }
            }
            Section("Mode") {
                checkRow("Light", selected: value.themeMode == ThemeMode.light) { component.onThemeModeChanged(mode: ThemeMode.light) }
                checkRow("Dark", selected: value.themeMode == ThemeMode.dark) { component.onThemeModeChanged(mode: ThemeMode.dark) }
                checkRow("Follow system", selected: value.themeMode == ThemeMode.auto) { component.onThemeModeChanged(mode: ThemeMode.auto) }
            }
        }
    }

    private var taskBehaviorDetail: some View {
        let value = settings.value
        return List {
            Section {
                Toggle(isOn: Binding(get: { value.dragAndDropEnabled }, set: { component.onDragAndDropChanged(enabled: $0) })) {
                    Text("Drag and drop").foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            } footer: {
                Text("Experimental — reorder tasks by dragging.")
            }
            Section("Keep done items visible — everywhere") {
                doneVisibilityPicker(current: Int(ShellBridgeKt.doneVisibilityGlobalSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setGlobalDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
            Section("Keep done items visible — on the dashboard") {
                doneVisibilityPicker(current: Int(ShellBridgeKt.doneVisibilityDashboardSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setDashboardDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
        }
    }

    private var speechDetail: some View {
        List {
            Section {
                Text("Dictation uses an on-device speech engine. There isn't one available on this device yet.")
                    .foregroundStyle(colors.inkMuted)
                    .listRowBackground(colors.surfaceCard)
            }
        }
    }

    private var agentDetail: some View {
        let value = inference.value
        return List {
            Section {
                // "Off" is always offered first (the default); then each engine registered on this device.
                // A cloud engine the Account isn't entitled to shows disabled, never selectable.
                agentRow(label: "Off", note: "The agent stays off. Nothing is sent anywhere.",
                         selected: ShellBridgeKt.inferenceOffSelected(state: value), locked: false) {
                    ShellBridgeKt.inferenceSelectOff(component: component)
                }
                ForEach(0..<Int(ShellBridgeKt.inferenceOptionCount(state: value)), id: \.self) { i in
                    let index = Int32(i)
                    agentRow(label: ShellBridgeKt.inferenceOptionLabel(state: value, index: index),
                             note: ShellBridgeKt.inferenceOptionNote(state: value, index: index),
                             selected: ShellBridgeKt.inferenceOptionSelected(state: value, index: index),
                             locked: ShellBridgeKt.inferenceOptionLocked(state: value, index: index)) {
                        ShellBridgeKt.inferenceSelectOption(component: component, state: value, index: index)
                    }
                }
            } header: {
                Text("Engine")
            } footer: {
                Text("The agent can turn a brain dump into draft tasks and suggest changes to your plan. Choose where it runs — or keep it off.")
            }
            Section {
                Toggle(isOn: Binding(get: { brainDumpNotifications }, set: { setBrainDumpNotifications($0) })) {
                    Text("Notify me when a brain dump is ready").foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            } header: {
                Text("Notifications")
            } footer: {
                Text("Get a notification when your drafts are ready to review — tapping it opens the Inbox.")
            }
        }
    }

    /// The Assistant enablement detail (#282, ADR-0040): the Owner's persistent disable / withdraw-consent
    /// row. The toggle calls the server seam; enabling carries the egress consent shown in the footer.
    private var assistantDetail: some View {
        let value = assistant.value
        return List {
            Section {
                Toggle(isOn: Binding(get: { value.enabled }, set: { component.onAssistantEnablementChanged(enabled: $0) })) {
                    Text("Enable the Assistant").foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
                .disabled(value.busy)
            } header: {
                Text("Assistant")
            } footer: {
                Text(value.disclosure)
            }
        }
        .onAppear {
            guard !brainDumpNotificationsSeeded else { return }
            brainDumpNotificationsSeeded = true
            brainDumpNotifications = ShellBridgeKt.brainDumpNotificationsEnabled(component: component)
        }
    }

    /// Persist the Brain dump notifications opt-in (#271). Enabling requests OS authorization (the consent);
    /// a denial reverts the toggle so it reflects reality (notifications won't fire) — handled gracefully.
    private func setBrainDumpNotifications(_ on: Bool) {
        if on {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
                DispatchQueue.main.async {
                    brainDumpNotifications = granted
                    ShellBridgeKt.setBrainDumpNotificationsEnabled(component: component, enabled: granted)
                }
            }
        } else {
            brainDumpNotifications = false
            ShellBridgeKt.setBrainDumpNotificationsEnabled(component: component, enabled: false)
        }
    }

    private var dataPrivacyDetail: some View {
        let value = settings.value
        return List {
            Section {
                Toggle(isOn: Binding(get: { value.trackingEnabled }, set: { component.onTrackingChanged(enabled: $0) })) {
                    Text("Analytics & tracking").foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            }
            Section {
                Button("Export or import your data") { component.onOpenDataExportImport() }
                    .listRowBackground(colors.surfaceCard)
            } footer: {
                Text("Your data is yours. Export or import it anytime on the web.")
            }
        }
    }

    private var legalDetail: some View {
        List {
            Section {
                Button("Terms of Service") { legalPage = LegalPage(title: "Terms of Service", url: Self.termsURL) }
                    .foregroundStyle(colors.onSurface).listRowBackground(colors.surfaceCard)
                Button("Privacy Policy") { legalPage = LegalPage(title: "Privacy Policy", url: Self.privacyURL) }
                    .foregroundStyle(colors.onSurface).listRowBackground(colors.surfaceCard)
            } footer: {
                Text("Deferno is open source under the Apache 2.0 license.")
            }
        }
        .sheet(item: $legalPage) { page in
            NavigationStack {
                LegalWebView(
                    url: page.url,
                    onContact: {
                        legalPage = nil
                        component.onOpenSubmitFeedback()
                    },
                    onAccountRemoval: { if let url = accountRemovalMailtoURL() { openURL(url) } })
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(page.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { legalPage = nil }
                    }
                }
            }
        }
    }

    private static let termsURL = URL(string: "https://www.defernowork.com/terms")!
    private static let privacyURL = URL(string: "https://www.defernowork.com/privacy")!

    private var accountDetail: some View {
        let value = settings.value
        return List {
            Section {
                labeledRow("Username", value.username ?? "—")
                labeledRow("Time zone", value.timeZone ?? "Device default")
            }
            Section {
                Button("View profile") { component.onOpenProfile() }
                    .listRowBackground(colors.surfaceCard)
            }
        }
    }

    private func linkDetail(text: String, action: String, perform: @escaping () -> Void) -> some View {
        List {
            Section {
                Button(action, action: perform).listRowBackground(colors.surfaceCard)
            } footer: {
                Text(text)
            }
        }
    }

    private func comingSoon(action: String?, perform: (() -> Void)?) -> some View {
        List {
            Section {
                Text("This is on the way. Thanks for your patience.")
                    .foregroundStyle(colors.inkMuted)
                    .listRowBackground(colors.surfaceCard)
                if let action, let perform {
                    Button(action, action: perform).listRowBackground(colors.surfaceCard)
                }
            }
        }
    }

    // MARK: Atoms

    /// A native single-select row: tap to choose, a checkmark marks the current value (the iOS idiom,
    /// replacing the old custom radio circle). Colour is reinforcement, never the sole signal (WCAG).
    private func checkRow(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(label).foregroundStyle(colors.onSurface)
                Spacer()
                if selected {
                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(colors.primary)
                }
            }
            .contentShape(Rectangle())
        }
        .listRowBackground(colors.surfaceCard)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func agentRow(label: String, note: String, selected: Bool, locked: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).foregroundStyle(locked ? colors.inkMuted : colors.onSurface)
                    Text(note).font(.caption).foregroundStyle(colors.inkMuted)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(colors.primary)
                } else if locked {
                    Image(systemName: "lock.fill").font(.caption).foregroundStyle(colors.inkMuted)
                }
            }
            .contentShape(Rectangle())
        }
        .disabled(locked)
        .listRowBackground(colors.surfaceCard)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func doneVisibilityPicker(current: Int, onSelect: @escaping (Int) -> Void) -> some View {
        let options: [(String, Int)] = [("1 day", 86400), ("3 days", 259200), ("1 week", 604800), ("Always", -1)]
        return Picker("Show for", selection: Binding(get: { current }, set: { onSelect($0) })) {
            ForEach(options, id: \.1) { Text($0.0).tag($0.1) }
        }
        .listRowBackground(colors.surfaceCard)
    }

    private func labeledRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(colors.onSurface)
            Spacer()
            Text(value).foregroundStyle(colors.inkMuted)
        }
        .listRowBackground(colors.surfaceCard)
    }

    private func title(_ category: SettingsCategory) -> String {
        switch ShellBridgeKt.settingsCategoryName(category: category) {
        case "Appearance": return "Appearance"
        case "TaskBehavior": return "Task behavior"
        case "SpeechEngine": return "Speech engine"
        case "Agent": return "Agent"
        case "Assistant": return "Assistant"
        case "DataPrivacy": return "Data & Privacy"
        case "HelpFeedback": return "Help & Feedback"
        case "AppPermissions": return "App Permissions"
        case "Legal": return "Legal"
        case "Account": return "Account"
        case "Security2FA": return "Security & 2FA"
        case "Integrations": return "Integrations"
        default: return ShellBridgeKt.settingsCategoryName(category: category)
        }
    }
}

/// A legal page to present in-app. Identifiable so it can drive `.sheet(item:)` (URL isn't).
private struct LegalPage: Identifiable {
    let title: String
    let url: URL
    var id: String { url.absoluteString }
}

/// Our hosted Terms/Privacy with the site chrome removed, shown in-app. The pages are server-rendered
/// with exactly one `<nav>` (header) and one `<footer>`; a document-start user script hides both
/// *before first paint*, so only the `<main>` content renders — no full-site flash (which Safari
/// Reader couldn't avoid). ponytail: tag selectors `nav,footer`; revisit only if the site adds chrome
/// outside those tags.
///
/// Tapped in-content links are inert (cancelled in the nav delegate) and visually flattened to plain
/// text (CSS above) — except the two email links, which stay styled. `accounts@` (account removal)
/// opens the mail app with a prefilled template (`onAccountRemoval`); any other `mailto:` (i.e.
/// `support@`) routes to the in-app feedback form (`onContact`). The links are Cloudflare-obfuscated:
/// they decode to `mailto:` once their script runs (almost always before a tap), or stay a
/// `/cdn-cgi/l/email-protection` URL if not — the CSS matches both; the delegate keys off the decoded
/// address, falling back to feedback for the rare un-decoded tap.
private struct LegalWebView: UIViewRepresentable {
    let url: URL
    let onContact: () -> Void
    let onAccountRemoval: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onContact: onContact, onAccountRemoval: onAccountRemoval) }

    func makeUIView(context: Context) -> WKWebView {
        // Hide site chrome (nav/footer) and flatten every link *except* the contact email into plain
        // text — no color/underline/tap — so the email is the only thing that reads as clickable.
        let hideChrome = WKUserScript(
            source: """
            var s = document.createElement('style');
            s.textContent = 'nav,footer{display:none!important} a:not([href^="mailto:"]):not([href*="email-protection"]){color:inherit!important;text-decoration:none!important;pointer-events:none!important}';
            document.documentElement.appendChild(s);
            """,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true)
        let controller = WKUserContentController()
        controller.addUserScript(hideChrome)
        let config = WKWebViewConfiguration()
        config.userContentController = controller
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let onContact: () -> Void
        let onAccountRemoval: () -> Void
        init(onContact: @escaping () -> Void, onAccountRemoval: @escaping () -> Void) {
            self.onContact = onContact
            self.onAccountRemoval = onAccountRemoval
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // Only intercept what the user taps; let the page's own load (and decode scripts) through.
            guard navigationAction.navigationType == .linkActivated,
                  let target = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel) // every tapped link is inert; the email links are handed off below
            let address = target.scheme == "mailto" ? target.absoluteString.dropFirst("mailto:".count) : ""
            if address.lowercased().hasPrefix("accounts@defernowork.com") {
                onAccountRemoval()
            } else if target.scheme == "mailto" || target.absoluteString.contains("email-protection") {
                onContact()
            }
        }
    }
}

/// The prefilled account-removal email for the `accounts@` link — opened via the system mail app.
private func accountRemovalMailtoURL() -> URL? {
    var components = URLComponents()
    components.scheme = "mailto"
    components.path = "accounts@defernowork.com"
    components.queryItems = [
        URLQueryItem(name: "subject", value: "Account removal request"),
        URLQueryItem(name: "body", value: """
            Hello,

            I'd like to request removal of my Deferno account and its associated data.

            Account email:

            Thank you.
            """),
    ]
    return components.url
}
