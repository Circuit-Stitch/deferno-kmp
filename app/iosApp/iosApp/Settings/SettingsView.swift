import Deferno
import SwiftUI

/// The Settings Destination (#72) — a native `NavigationStack` over an inset-grouped category list that
/// pushes one screen per category (native `‹ Settings` back + swipe-back come free, no custom "Back").
/// A thin renderer of `SettingsComponent`: backed categories read/write the Active Account's
/// `UserSettings` (Appearance applies the theme live), the unbacked ones (Security & 2FA, Integrations)
/// are gentle coming-soon stubs, and host concerns (export/import, feedback, app permissions, console)
/// are forwarded for the shell to deep-link. The SpeechEngine + Agent rows stay hidden until a real
/// device engine is registered (#95/#150). Navigation is SwiftUI-native here — the component's
/// List↔Detail stack isn't used; the drill resets to the root list when you leave Settings, the iOS norm.
struct SettingsView: View {
    let component: SettingsComponent
    @StateObject private var settings: StateFlowObserver<UserSettings>
    @StateObject private var speech: StateFlowObserver<SpeechEngineSettings>
    @StateObject private var inference: StateFlowObserver<InferenceEngineSettings>
    @Environment(\.defernoColors) private var colors

    init(component: SettingsComponent) {
        self.component = component
        _settings = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.settingsStateBridge(component: component)))
        _speech = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.speechEngineBridge(component: component)))
        _inference = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.inferenceEngineBridge(component: component)))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(categories) { category in
                        NavigationLink {
                            detailScreen(category)
                        } label: {
                            HStack {
                                Text(title(category)).foregroundStyle(colors.onSurface)
                                if !ShellBridgeKt.settingsCategoryBacked(category: category) {
                                    Spacer()
                                    Text("Coming soon").font(.subheadline).foregroundStyle(colors.inkMuted)
                                }
                            }
                        }
                        .listRowBackground(colors.surfaceCard)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(colors.background)
            .shellNavBar("Settings")
        }
    }

    /// Hide the device-local engine rows until one is registered (matches Android): SpeechEngine until an
    /// iOS speech engine ships (#95), Agent until an inference engine is available (#150) — else the row
    /// would open an empty "coming soon".
    private var categories: [SettingsCategory] {
        ShellBridgeKt.settingsCategories().filter { category in
            switch ShellBridgeKt.settingsCategoryName(category: category) {
            case "SpeechEngine": return speech.value.available
            case "Agent": return inference.value.available
            default: return true
            }
        }
    }

    // MARK: Pushed detail screen (native back + swipe-back)

    private func detailScreen(_ category: SettingsCategory) -> some View {
        detail(category)
            .scrollContentBackground(.hidden)
            .background(colors.background)
            .navigationTitle(title(category))
            .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func detail(_ category: SettingsCategory) -> some View {
        switch ShellBridgeKt.settingsCategoryName(category: category) {
        case "Appearance": appearanceDetail
        case "TaskBehavior": taskBehaviorDetail
        case "SpeechEngine": speechDetail
        case "Agent": agentDetail
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
                checkRow("Deferno", selected: value.themeFamily === ThemeFamily.deferno) { component.onThemeFamilyChanged(family: ThemeFamily.deferno) }
                checkRow("Mono", selected: value.themeFamily === ThemeFamily.mono) { component.onThemeFamilyChanged(family: ThemeFamily.mono) }
            }
            Section("Mode") {
                checkRow("Light", selected: value.themeMode === ThemeMode.light) { component.onThemeModeChanged(mode: ThemeMode.light) }
                checkRow("Dark", selected: value.themeMode === ThemeMode.dark) { component.onThemeModeChanged(mode: ThemeMode.dark) }
                checkRow("Follow system", selected: value.themeMode === ThemeMode.auto_) { component.onThemeModeChanged(mode: ThemeMode.auto_) }
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
                Text("Terms of Service").foregroundStyle(colors.onSurface).listRowBackground(colors.surfaceCard)
                Text("Privacy Policy").foregroundStyle(colors.onSurface).listRowBackground(colors.surfaceCard)
            } footer: {
                Text("Deferno is open source under the Apache 2.0 license.")
            }
        }
    }

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
