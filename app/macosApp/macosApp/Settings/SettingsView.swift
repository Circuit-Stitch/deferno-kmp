import AppKit
import Deferno
import SwiftUI
import UniformTypeIdentifiers
import UserNotifications

/// The Settings Destination (#72) — a tier-3 drill-down (`SettingsChild`: List ↔ Detail(category)). A
/// thin renderer of `SettingsComponent`: the backed categories read/write the Active Account's
/// `UserSettings` (Appearance applies the theme live), the two unbacked ones (Security & 2FA,
/// Integrations) are gentle coming-soon stubs, data export/import builds and restores an on-device
/// Backup file in-app (#313/#314, ADR-0041), and the remaining host concerns (feedback, app
/// permissions, console) are forwarded for the shell to deep-link. The SpeechEngine row is hidden
/// until a real macOS engine ships (#95); the Agent row until an inference engine is registered (#150).
/// Mirrors iOS's `SettingsView` — with the desktop affordances substituted where UIKit has no Mac twin
/// (#368): `NSSavePanel` for the share sheet, `.fileImporter` for the document picker, and the hosted
/// legal pages opened in the browser rather than embedded in a `WKWebView`.
struct SettingsView: View {
    let component: SettingsComponent
    @StateObject private var stack: StateFlowObserver<SettingsComponentSettingsChild>
    @StateObject private var settings: StateFlowObserver<UserSettings>
    @StateObject private var speech: StateFlowObserver<SpeechEngineSettings>
    // The Agent's device-local inference-engine choice (#150, ADR-0027). The macOS option set is smaller
    // than iOS's — Koog publishes no macosArm64 klib, so only the on-device Foundation Models engine is
    // registered — but the index-based bridge accessors absorb that with no Swift change.
    @StateObject private var inference: StateFlowObserver<InferenceEngineSettings>
    // The server-mediated Assistant enablement (#282, ADR-0040): the Owner's persistent disable /
    // withdraw-consent row, shown only when the Org is entitled.
    @StateObject private var assistant: StateFlowObserver<AssistantSettings>
    // On-device storage usage (#211): the Storage read-out's kept brain-dump recordings + total, and the
    // active storage-provider read-out.
    @StateObject private var storage: StateFlowObserver<StorageUsage>
    @StateObject private var provider: StateFlowObserver<StorageProviderSettings>
    @Environment(\.defernoColors) private var colors
    // The "Brain dump notifications" opt-in (#271): device-local, seeded once from the AppScope preference;
    // the toggle persists through the component and requests OS authorization on enable.
    @State private var brainDumpNotifications = false
    @State private var brainDumpNotificationsSeeded = false
    // The "keep brain-dump recordings" choice (#211): device-local, seeded once from the component (default on).
    @State private var keepRecordings = true
    @State private var keepRecordingsSeeded = false
    // On-device data export/import (#313/#314, ADR-0041): the Export/Import action sheet, the import file
    // picker, and the outcome message shown after the shared engine replays the Backup file's items.
    @State private var showExportDialog = false
    @State private var showImportPicker = false
    @State private var showImportResult = false
    @State private var importResultText = ""

    init(component: SettingsComponent) {
        self.component = component
        _stack = StateObject(wrappedValue: StateFlowObserver(component.activeChild))
        _settings = StateObject(wrappedValue: StateFlowObserver(component.settings))
        _speech = StateObject(wrappedValue: StateFlowObserver(component.speechEngine))
        _inference = StateObject(wrappedValue: StateFlowObserver(component.inferenceEngine))
        _assistant = StateObject(wrappedValue: StateFlowObserver(component.assistant))
        _storage = StateObject(wrappedValue: StateFlowObserver(component.storageUsage))
        _provider = StateObject(wrappedValue: StateFlowObserver(component.storageProvider))
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
            // Likewise the Agent row until an inference engine is registered (#150), else it would open an
            // empty chooser. On macOS today the Foundation Models engine is always registered, so this is
            // effectively always true — the arm exists so the behaviour matches iOS if the catalog empties.
            case "Agent": return inference.value.available
            // The Assistant row shows only once the Org is entitled (ADR-0040); hidden otherwise.
            case "Assistant": return assistant.value.available
            // Storage is deliberately NOT gated: on-device storage is always available.
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
        case "Agent": agentDetail
        case "Assistant": assistantDetail
        case "Storage": storageDetail
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

    // MARK: Agent (#150, ADR-0027)

    /// The Agent's inference-engine chooser: "Off" (the default) plus each engine registered on this
    /// device, over the index-based bridge accessors — no `core:agent` type crosses into Swift.
    private var agentDetail: some View {
        VStack(alignment: .leading, spacing: 16) {
            section(L.string("settings_speech_engine_label")) {
                engineRows
            }
            Text(L.string("settings_agent_intro")).font(.subheadline).foregroundStyle(colors.inkMuted)
            section(L.string("settings_notifications_section")) {
                Toggle(isOn: Binding(get: { brainDumpNotifications }, set: { setBrainDumpNotifications($0) })) {
                    Text(L.string("settings_notify_braindump_label")).foregroundStyle(colors.onSurface)
                }
                Text(L.string("settings_notify_braindump_description"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Seeded HERE, on the pane that owns the toggle. iOS attaches this to `assistantDetail` instead
        // (SettingsView.swift:242) — a defect that leaves the toggle unseeded for anyone who opens Agent
        // without first opening Assistant. Don't copy the placement.
        .onAppear {
            guard !brainDumpNotificationsSeeded else { return }
            brainDumpNotificationsSeeded = true
            brainDumpNotifications = ShellBridgeKt.brainDumpNotificationsEnabled(component: component)
        }
    }

    @ViewBuilder
    private var engineRows: some View {
        let value = inference.value
        // "Off" is always offered first (the default); then each engine registered on this device.
        // A cloud engine the Account isn't entitled to shows disabled, never selectable.
        agentRow(label: L.string("settings_agent_engine_off"), note: L.string("settings_agent_off_note"),
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

    // MARK: Storage (#210/#211/#311)

    /// The Storage detail: on-device usage (kept brain-dump recordings + total, largest first), the
    /// biggest-attachments deep-link into Search, a read-only storage-provider read-out, and the
    /// keep-recordings toggle. Offline-first — every figure comes from the on-device store, never the network.
    private var storageDetail: some View {
        VStack(alignment: .leading, spacing: 16) {
            onDeviceUsageSection
            allItemsSection
            storageProviderSection
            section(L.string("braindump_title")) {
                Toggle(isOn: Binding(get: { keepRecordings }, set: { setKeepRecordings($0) })) {
                    Text(L.string("settings_storage_keep_recordings_label")).foregroundStyle(colors.onSurface)
                }
                Text(L.string("settings_storage_keep_recordings_description"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            guard !keepRecordingsSeeded else { return }
            keepRecordingsSeeded = true
            keepRecordings = ShellBridgeKt.keepBrainDumpRecordingsEnabled(component: component)
        }
    }

    private var onDeviceUsageSection: some View {
        let usage = storage.value
        return section(L.string("settings_storage_on_device_section")) {
            // An explicit HStack rather than `labeledRow`: this file's atom mutes the *label* and not the
            // value, which is the wrong emphasis for a rollup line (the label is the subject here).
            HStack {
                Text(L.string("settings_storage_recordings_section")).foregroundStyle(colors.onSurface)
                Spacer()
                Text(storageSummary(count: Int(usage.count), bytes: usage.totalBytes))
                    .foregroundStyle(colors.inkMuted)
            }
            .font(.subheadline)
            // The per-recording rows are empty on macOS today — nothing here captures a brain dump yet
            // (#368 Tranche 5). The seam itself is live (RootComponent wires `onDeviceStorageUsage`), so
            // the rollup honestly reads "None" rather than the section being faked or hidden.
            ForEach(usage.recordings, id: \.id) { rec in
                recordingRow(taskId: rec.taskId, createdAtEpochMs: rec.createdAtEpochMs, sizeBytes: rec.sizeBytes)
            }
            Text(L.string("settings_storage_recordings_description"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
    }

    /// Takes the row's fields rather than the bridged `StorageUsage.Recording` itself — the Obj-C header
    /// flattens the nested Kotlin class, and nothing here needs to name it.
    private func recordingRow(taskId: String?, createdAtEpochMs: Int64, sizeBytes: Int64) -> some View {
        Button { component.onOpenRecording(taskId: taskId) } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.string("braindump_recording_status")).foregroundStyle(colors.onSurface)
                    Text(recordingDate(createdAtEpochMs)).font(.caption).foregroundStyle(colors.inkMuted)
                }
                Spacer()
                Text(formatBytes(sizeBytes)).foregroundStyle(colors.inkMuted)
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
            }
            .font(.subheadline)
            .frame(minHeight: Layout.minTouchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Logical attachments across ALL items (#311) — distinct from the on-device bytes above:
    /// backend-hosted attachments occupy no device storage, so this deep-links into Search, filtered to
    /// items with attachments and sorted biggest-first.
    private var allItemsSection: some View {
        section(L.string("settings_storage_all_items_section")) {
            Button { component.onOpenBiggestAttachments() } label: {
                HStack {
                    Text(L.string("search_sort_biggest_attachments")).foregroundStyle(colors.onSurface)
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
                }
                .font(.subheadline)
                .frame(minHeight: Layout.minTouchTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Text(L.string("settings_storage_all_items_description"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
    }

    private var storageProviderSection: some View {
        section(L.string("settings_storage_provider_label")) {
            labeledRow(L.string("settings_storage_provider_label"), ShellBridgeKt.storageActiveProviderName(state: provider.value))
            comingLaterRow(L.string("settings_storage_provider_deferno_backend"))
            comingLaterRow(L.string("settings_storage_provider_dropbox"))
            comingLaterRow(L.string("settings_storage_provider_google_drive"))
            // The key name says "ios" but the copy is platform-neutral ("New attachments are kept on this
            // device…") — reused verbatim rather than minting a macOS twin.
            Text(L.string("settings_storage_ios_note"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
    }

    /// Persist the keep-recordings choice (#211) — device-local, never synced.
    private func setKeepRecordings(_ on: Bool) {
        keepRecordings = on
        ShellBridgeKt.setKeepBrainDumpRecordings(component: component, enabled: on)
    }

    // MARK: Data & Privacy — on-device Backup export/import (#313/#314, ADR-0041)

    private var dataPrivacyDetail: some View {
        let value = settings.value
        return VStack(alignment: .leading, spacing: 16) {
            Toggle(isOn: Binding(get: { value.trackingEnabled }, set: { component.onTrackingChanged(enabled: $0) })) {
                Text(L.string("settings_privacy_analytics_label")).foregroundStyle(colors.onSurface)
            }
            VStack(alignment: .leading, spacing: 8) {
                Button(L.string("settings_data_export_web_button")) { showExportDialog = true }
                    .buttonStyle(.bordered)
                Text(L.string("settings_data_export_import_description"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .confirmationDialog(L.string("settings_data_your_data_section"), isPresented: $showExportDialog, titleVisibility: .visible) {
            Button(L.string("settings_data_export_menu_export")) { runExport() }
            Button(L.string("settings_data_export_menu_full_backup")) {}.disabled(true)
            Button(L.string("settings_import_button")) { showImportPicker = true }
            Button(L.string("common_cancel"), role: .cancel) {}
        }
        // Import (#314): the picker accepts a Backup-file zip; its bytes cross to the shared engine. SwiftUI
        // owns the presentation, so it can't race the confirmation dialog's dismissal the way a bare
        // `NSOpenPanel.runModal()` from the button action would.
        .fileImporter(isPresented: $showImportPicker, allowedContentTypes: [.zip], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first { runImport(url) }
        }
        .alert(L.string("common_import"), isPresented: $showImportResult) {
            Button(L.string("common_ok"), role: .cancel) {}
        } message: {
            Text(importResultText)
        }
    }

    /// Build the on-device Backup zip on the shared side (#313, ADR-0041), then let the person choose where
    /// to save it — a Mac saves a file rather than sharing one, so this is `NSSavePanel` where iOS presents
    /// a `UIActivityViewController`. The bridge calls back on the main thread (and after the confirmation
    /// dialog has dismissed), so running the panel from here is safe.
    private func runExport() {
        ShellBridgeKt.exportBackup(component: component) { nsData in
            guard let nsData else { return }
            let panel = NSSavePanel()
            panel.nameFieldStringValue = "deferno-backup.zip"
            panel.allowedContentTypes = [.zip]
            panel.canCreateDirectories = true
            guard panel.runModal() == .OK, let url = panel.url else { return }
            try? (nsData as Data).write(to: url, options: .atomic)
        }
    }

    /// Restore items from a picked Backup file (#314, ADR-0041): read the security-scoped file's bytes (the
    /// same start/stop-access bracket `FeedbackView` uses for attachments), hand them to the shared engine,
    /// and show the outcome. The bridge reports `(kind, count)` on the main thread, so mutating the alert
    /// state is safe.
    private func runImport(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            importResultText = L.string("settings_import_error_unreadable")
            showImportResult = true
            return
        }
        ShellBridgeKt.importBackup(component: component, data: data) { kind, count in
            switch kind {
            case "restored":
                let n = Int(truncating: count) // count crosses as a boxed KotlinInt (NSNumber)
                importResultText = n == 0
                    ? L.string("settings_import_error_empty")
                    : L.plural("settings_import_restored", n)
            case "force_upgrade":
                importResultText = L.string("settings_import_error_too_new")
            case "unsupported":
                importResultText = L.string("settings_import_error_too_old")
            default:
                importResultText = L.string("settings_import_error_unreadable")
            }
            showImportResult = true
        }
    }

    // MARK: Legal

    /// Our hosted Terms/Privacy, opened in the default browser. iOS embeds them in a chrome-stripping
    /// `WKWebView` because App Review expects in-app presentation; the Mac has no such constraint, so the
    /// browser is both the honest affordance and one less user script to maintain.
    private var legalDetail: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextLink(title: L.string("settings_legal_terms_section")) { openInBrowser(Self.termsURL) }
            TextLink(title: L.string("settings_legal_privacy_policy_title")) { openInBrowser(Self.privacyURL) }
            // iOS reaches account removal by tapping the accounts@ link *inside* the hosted page, which its
            // web view intercepts. With the page in the browser there is nothing to intercept, so the row
            // has to be explicit. There is no dedicated label key in the catalog — the email subject
            // ("Account removal request") is already the right words, and is translated in all 5 locales.
            TextLink(title: L.string("settings_legal_account_removal_email_subject")) {
                if let url = accountRemovalMailtoURL() { openInBrowser(url) }
            }
            Text(L.string("settings_legal_open_source_apache_body"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private static let termsURL = URL(string: "https://www.defernowork.com/terms")!
    private static let privacyURL = URL(string: "https://www.defernowork.com/privacy")!

    private func openInBrowser(_ url: URL) { _ = NSWorkspace.shared.open(url) }

    // MARK: Account switcher (#368 G7)

    /// The Account category is an **account switcher**: the signed-in roster — click the active account
    /// (chevron) to open its Profile (where identity + sign-out live), click another to switch to it — plus
    /// "Add another account" (re-enters sign-in, keeping the others). Time zone moved to Profile too.
    ///
    /// The roster is read as a snapshot, not observed: switching or adding an account re-keys the whole Main
    /// shell, which destroys this View anyway.
    private var accountDetail: some View {
        let active = ShellBridgeKt.settingsActiveAccountKey(component: component)
        return VStack(alignment: .leading, spacing: 12) {
            section(L.string("shell_signed_in")) {
                ForEach(ShellBridgeKt.settingsAccounts(component: component)) { account in
                    accountRow(account, isActive: ShellBridgeKt.accountKey(account: account) == active)
                }
            }
            Text(L.string("settings_accounts_intro"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
            // Re-enters the Auth shell with a non-nil `onCancel`, which `RootView` threads into `SignInView`
            // as the Cancel-back — without that pairing this button is a one-way door on macOS.
            Button { component.onAddAccount() } label: {
                Label(L.string("auth_add_another_account"), systemImage: "plus")
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func accountRow(_ account: Account, isActive: Bool) -> some View {
        Button {
            if isActive {
                component.onOpenProfile()
            } else {
                ShellBridgeKt.switchSettingsAccount(component: component, account: account)
            }
        } label: {
            HStack {
                Text(account.label).foregroundStyle(colors.onSurface)
                Spacer()
                if isActive {
                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(colors.primary)
                    // The chevron marks the active row as a drill-in to its Profile.
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
                }
            }
            .frame(minHeight: Layout.minTouchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
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

    /// An engine row: label + why-you'd-pick-it note, a checkmark on the current choice, a padlock on one
    /// the Account isn't entitled to (which is also disabled). Colour is reinforcement, never the sole
    /// signal — the glyph carries it (WCAG).
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
            .frame(minHeight: Layout.minTouchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(locked)
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

    /// A muted, non-clickable provider row marked "Coming later" (the Storage read-out's roadmap, #211).
    private func comingLaterRow(_ label: String) -> some View {
        HStack {
            Text(label).foregroundStyle(colors.inkMuted)
            Spacer()
            Text(L.string("settings_storage_note_coming_later")).foregroundStyle(colors.inkMuted)
        }
        .font(.subheadline)
    }

    /// "3 items · 4.2 MB", or "None" when nothing is on the device.
    private func storageSummary(count: Int, bytes: Int64) -> String {
        guard count > 0 else { return L.string("common_none") }
        let items = L.plural("calendar_day_item_count", count)
        return "\(items) · \(formatBytes(bytes))"
    }

    private func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func recordingDate(_ epochMs: Int64) -> String {
        Date(timeIntervalSince1970: Double(epochMs) / 1000).formatted(date: .abbreviated, time: .shortened)
    }

    private func title(_ category: SettingsCategory) -> String {
        L.settingsCategoryLabel(ShellBridgeKt.settingsCategoryName(category: category))
    }
}

/// The prefilled account-removal email for the `accounts@` row — opened via the system mail app.
private func accountRemovalMailtoURL() -> URL? {
    var components = URLComponents()
    components.scheme = "mailto"
    components.path = "accounts@defernowork.com"
    components.queryItems = [
        URLQueryItem(name: "subject", value: L.string("settings_legal_account_removal_email_subject")),
        URLQueryItem(name: "body", value: L.string("settings_legal_account_removal_email_body")),
    ]
    return components.url
}
