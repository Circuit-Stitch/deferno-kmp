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
    @StateObject private var stack: StateFlowObserver<SettingsComponentSettingsChild>
    @StateObject private var settings: StateFlowObserver<UserSettings>
    @StateObject private var speech: StateFlowObserver<SpeechEngineSettings>
    // The server-mediated Assistant enablement (#282, ADR-0040): the Owner's persistent disable /
    // withdraw-consent row, shown only when the Org is entitled.
    @StateObject private var assistant: StateFlowObserver<AssistantSettings>
    @Environment(\.defernoColors) private var colors

    init(component: SettingsComponent) {
        self.component = component
        _stack = StateObject(wrappedValue: StateFlowObserver(component.activeChild))
        _settings = StateObject(wrappedValue: StateFlowObserver(component.settings))
        _speech = StateObject(wrappedValue: StateFlowObserver(component.speechEngine))
        _assistant = StateObject(wrappedValue: StateFlowObserver(component.assistant))
    }

    // No PaneHeader: the single adaptive shell bar (MainShellView) titles "Settings" / the category and
    // drives ← back (via the shell's onBack) — the chrome reflects this stack's foreground child.
    var body: some View {
        VStack(spacing: 0) {
            if let category = ShellBridgeKt.settingsChildCategory(child: stack.value) {
                ScrollView { detail(category).padding(Layout.gutter) }
            } else {
                categoryList
            }
        }
        .background(colors.background)
    }

    // MARK: List

    private var categoryList: some View {
        let categories = ShellBridgeKt.settingsCategories().filter { category in
            switch ShellBridgeKt.settingsCategoryName(category: category) {
            // Hide the SpeechEngine row until a real engine is registered on the device (#95).
            case "SpeechEngine": return speech.value.available
            // The Assistant row shows only once the Org is entitled (ADR-0040); hidden otherwise.
            case "Assistant": return assistant.value.available
            default: return true
            }
        }
        return List {
            ForEach(categories) { category in
                Button { component.openCategory(category: category) } label: {
                    HStack {
                        Text(title(category)).foregroundStyle(colors.onSurface)
                        Spacer()
                        if !ShellBridgeKt.settingsCategoryBacked(category: category) {
                            Text(L.string("settings_coming_soon_title")).font(.caption).foregroundStyle(colors.inkMuted)
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
        case "Assistant": assistantDetail
        case "DataPrivacy": dataPrivacyDetail
        case "HelpFeedback": linkDetail(text: L.string("settings_help_row_subtitle"), action: L.string("feedback_title")) { component.onOpenSubmitFeedback() }
        case "AppPermissions": linkDetail(text: L.string("settings_permissions_intro"), action: L.string("settings_permissions_open_button")) { component.onOpenAppPermissions() }
        case "Legal": legalDetail
        case "Account": accountDetail
        case "Security2FA": comingSoon(action: L.string("settings_security_open_console_button")) { component.onOpenConsole() }
        default: comingSoon(action: nil, perform: nil)
        }
    }

    private var appearanceDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 16) {
            section(L.string("settings_appearance_theme_section")) {
                radioRow(L.string("common_app_name"), selected: value.themeFamily == ThemeFamily.deferno) { component.onThemeFamilyChanged(family: ThemeFamily.deferno) }
                radioRow(L.string("settings_theme_family_mono"), selected: value.themeFamily == ThemeFamily.mono) { component.onThemeFamilyChanged(family: ThemeFamily.mono) }
            }
            section(L.string("settings_appearance_mode_section")) {
                radioRow(L.string("settings_theme_mode_light"), selected: value.themeMode == ThemeMode.light) { component.onThemeModeChanged(mode: ThemeMode.light) }
                radioRow(L.string("settings_theme_mode_dark"), selected: value.themeMode == ThemeMode.dark) { component.onThemeModeChanged(mode: ThemeMode.dark) }
                radioRow(L.string("settings_theme_mode_follow_system"), selected: value.themeMode == ThemeMode.auto) { component.onThemeModeChanged(mode: ThemeMode.auto) }
            }
        }
    }

    private var taskBehaviorDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: Binding(get: { value.dragAndDropEnabled }, set: { component.onDragAndDropChanged(enabled: $0) })) {
                Text(L.string("settings_task_behavior_drag_drop_label")).foregroundStyle(colors.onSurface)
            }
            section(L.string("settings_show_done_everywhere_label")) {
                doneVisibilityRow(current: Int(ShellBridgeKt.doneVisibilityGlobalSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setGlobalDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
            section(L.string("settings_show_done_dashboard_label")) {
                doneVisibilityRow(current: Int(ShellBridgeKt.doneVisibilityDashboardSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setDashboardDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
        }
    }

    private var speechDetail: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.string("settings_speech_none_body"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
    }

    /// The Assistant enablement detail (#282, ADR-0040): the Owner's persistent disable / withdraw-consent
    /// row. The toggle calls the server seam; enabling carries the egress consent shown beneath it.
    private var assistantDetail: some View {
        let value = assistant.value
        return VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: Binding(get: { value.enabled }, set: { component.onAssistantEnablementChanged(enabled: $0) })) {
                Text(L.string("settings_assistant_enable_label")).foregroundStyle(colors.onSurface)
            }
            .disabled(value.busy)
            Text(value.disclosure)
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var dataPrivacyDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: Binding(get: { value.trackingEnabled }, set: { component.onTrackingChanged(enabled: $0) })) {
                Text(L.string("settings_privacy_analytics_label")).foregroundStyle(colors.onSurface)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text(L.string("settings_data_web_note"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
                Button(L.string("settings_data_export_web_button")) { component.onOpenDataExportImport() }
                    .buttonStyle(.bordered)
            }
        }
    }

    private var legalDetail: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.string("settings_legal_terms_section")).font(.headline).foregroundStyle(colors.onSurface)
            Text(L.string("settings_legal_privacy_policy_title")).font(.headline).foregroundStyle(colors.onSurface)
            Text(L.string("settings_legal_open_source_apache_body"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var accountDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 12) {
            labeledRow(L.string("profile_username_label"), value.username ?? "—")
            labeledRow(L.string("profile_account_time_zone_label"), value.timeZone ?? L.string("profile_account_time_zone_default"))
            Button(L.string("profile_view_button")) { component.onOpenProfile() }
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
            Text(L.string("settings_coming_soon_generic_body"))
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
        let options: [(String, Int)] = [
            (L.string("settings_done_visibility_one_day"), 86400),
            (L.string("settings_done_visibility_three_days"), 259200),
            (L.string("settings_done_visibility_one_week"), 604800),
            (L.string("settings_done_visibility_always"), -1)
        ]
        return HStack(spacing: 8) {
            ForEach(options, id: \.1) { option in
                SelectableChip(label: option.0, selected: option.1 == current, prominence: .low, compact: true) {
                    onSelect(option.1)
                }
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
        L.settingsCategoryLabel(ShellBridgeKt.settingsCategoryName(category: category))
    }
}
