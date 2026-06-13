import Deferno
import SwiftUI

/// The Settings Destination (#72) — a tier-3 drill-down (`SettingsChild`: List ↔ Detail(category)). A
/// thin renderer of `SettingsComponent`: the backed categories read/write the Active Account's
/// `UserSettings` (Appearance applies the theme live), the two unbacked ones (Security & 2FA,
/// Integrations) are gentle coming-soon stubs, and host concerns (export/import, feedback, app
/// permissions, console) are forwarded for the shell to deep-link. The SpeechEngine row is hidden
/// until a real iOS engine ships (#95).
struct SettingsView: View {
    let component: SettingsComponent
    @StateObject private var stack: SettingsStackObserver
    @StateObject private var settings: StateFlowObserver<UserSettings>
    @StateObject private var speech: StateFlowObserver<SpeechEngineSettings>
    @Environment(\.defernoColors) private var colors

    init(component: SettingsComponent) {
        self.component = component
        _stack = StateObject(wrappedValue: SettingsStackObserver(ShellBridgeKt.settingsStackBridge(component: component)))
        _settings = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.settingsStateBridge(component: component)))
        _speech = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.speechEngineBridge(component: component)))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let category = ShellBridgeKt.settingsChildCategory(child: stack.active) {
                PaneHeader(title: title(category), onBack: { _ = component.onBack() })
                ScrollView { detail(category).padding(Layout.gutter) }
            } else {
                PaneHeader(title: "Settings")
                categoryList
            }
        }
        .background(colors.background)
    }

    // MARK: List

    private var categoryList: some View {
        let categories = ShellBridgeKt.settingsCategories().filter { category in
            // Hide the SpeechEngine row until a real engine is registered on the device (#95).
            ShellBridgeKt.settingsCategoryName(category: category) != "SpeechEngine" || speech.value.available
        }
        return List {
            ForEach(categories) { category in
                Button { component.openCategory(category: category) } label: {
                    HStack {
                        Text(title(category)).foregroundStyle(colors.onSurface)
                        Spacer()
                        if !ShellBridgeKt.settingsCategoryBacked(category: category) {
                            Text("Coming soon").font(.caption).foregroundStyle(colors.inkMuted)
                        }
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
                    }
                    .frame(minHeight: Layout.minTouchTarget)
                }
                .listRowBackground(colors.surface)
            }
        }
        .listStyle(.plain)
    }

    // MARK: Detail per category

    @ViewBuilder
    private func detail(_ category: SettingsCategory) -> some View {
        switch ShellBridgeKt.settingsCategoryName(category: category) {
        case "Appearance": appearanceDetail
        case "TaskBehavior": taskBehaviorDetail
        case "SpeechEngine": speechDetail
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
        return VStack(alignment: .leading, spacing: 16) {
            section("Theme") {
                radioRow("Deferno", selected: value.themeFamily === ThemeFamily.deferno) { component.onThemeFamilyChanged(family: ThemeFamily.deferno) }
                radioRow("Mono", selected: value.themeFamily === ThemeFamily.mono) { component.onThemeFamilyChanged(family: ThemeFamily.mono) }
            }
            section("Mode") {
                radioRow("Light", selected: value.themeMode === ThemeMode.light) { component.onThemeModeChanged(mode: ThemeMode.light) }
                radioRow("Dark", selected: value.themeMode === ThemeMode.dark) { component.onThemeModeChanged(mode: ThemeMode.dark) }
                radioRow("Follow system", selected: value.themeMode === ThemeMode.auto_) { component.onThemeModeChanged(mode: ThemeMode.auto_) }
            }
        }
    }

    private var taskBehaviorDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: Binding(get: { value.dragAndDropEnabled }, set: { component.onDragAndDropChanged(enabled: $0) })) {
                Text("Drag and drop (experimental)").foregroundStyle(colors.onSurface)
            }
            section("Keep done items visible — everywhere") {
                doneVisibilityRow(current: Int(ShellBridgeKt.doneVisibilityGlobalSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setGlobalDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
            section("Keep done items visible — on the dashboard") {
                doneVisibilityRow(current: Int(ShellBridgeKt.doneVisibilityDashboardSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setDashboardDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
        }
    }

    private var speechDetail: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Dictation uses an on-device speech engine. There isn't one available on this device yet.")
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
    }

    private var dataPrivacyDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: Binding(get: { value.trackingEnabled }, set: { component.onTrackingChanged(enabled: $0) })) {
                Text("Analytics & tracking").foregroundStyle(colors.onSurface)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Your data is yours. Export or import it anytime on the web.")
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
                Button("Export or import your data") { component.onOpenDataExportImport() }
                    .buttonStyle(.bordered)
            }
        }
    }

    private var legalDetail: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Terms of Service").font(.headline).foregroundStyle(colors.onSurface)
            Text("Privacy Policy").font(.headline).foregroundStyle(colors.onSurface)
            Text("Deferno is open source under the Apache 2.0 license.")
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var accountDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 12) {
            labeledRow("Username", value.username ?? "—")
            labeledRow("Time zone", value.timeZone ?? "Device default")
            Button("View profile") { component.onOpenProfile() }
                .buttonStyle(.bordered)
        }
    }

    private func linkDetail(text: String, action: String, perform: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(text).font(.subheadline).foregroundStyle(colors.inkMuted)
            Button(action, action: perform).buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func comingSoon(action: String?, perform: (() -> Void)?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("This is on the way. Thanks for your patience.")
                .font(.subheadline).foregroundStyle(colors.inkMuted)
            if let action, let perform {
                Button(action, action: perform).buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Atoms

    private func section<Content: View>(_ heading: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(heading).font(.subheadline.weight(.semibold)).foregroundStyle(colors.inkMuted)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }

    private func radioRow(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? colors.primary : colors.inkMuted)
                Text(label).foregroundStyle(colors.onSurface)
                Spacer()
            }
            .frame(minHeight: Layout.minTouchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func doneVisibilityRow(current: Int, onSelect: @escaping (Int) -> Void) -> some View {
        let options: [(String, Int)] = [("1 day", 86400), ("3 days", 259200), ("1 week", 604800), ("Always", -1)]
        return HStack(spacing: 8) {
            ForEach(options, id: \.1) { option in
                let selected = option.1 == current
                Button(option.0) { onSelect(option.1) }
                    .font(.footnote)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(selected ? colors.primaryContainer : colors.surfaceVariant, in: Capsule())
                    .foregroundStyle(colors.onSurface)
            }
        }
    }

    private func labeledRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(colors.inkMuted)
            Spacer()
            Text(value).foregroundStyle(colors.onSurface)
        }
        .font(.subheadline)
    }

    private func title(_ category: SettingsCategory) -> String {
        switch ShellBridgeKt.settingsCategoryName(category: category) {
        case "Appearance": return "Appearance"
        case "TaskBehavior": return "Task behavior"
        case "SpeechEngine": return "Speech engine"
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
