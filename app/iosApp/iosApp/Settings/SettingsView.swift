import Deferno
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications
import WebKit

/// The Settings Destination (#72) — a **tier-3 drill-down** (`SettingsChild`: List ↔ Detail(category))
/// rendered from the shared component's stack, so the single adaptive shell bar (`MainShellView`) titles
/// it ("Settings" / the category) and drives ← back. A thin renderer of `SettingsComponent`: backed
/// categories read/write the Active Account's `UserSettings` (Appearance applies the theme live), the
/// unbacked ones (Security & 2FA, Integrations) are gentle coming-soon stubs, data export builds an
/// on-device Backup file in-app for the share sheet (#313, ADR-0041), and the remaining host concerns
/// (feedback, app permissions, console) are forwarded for the shell to deep-link. The
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
    // On-device storage usage (#211): the Storage read-out's kept brain-dump recordings + total, and the
    // active storage-provider read-out.
    @StateObject private var storage: StateFlowObserver<StorageUsage>
    @StateObject private var provider: StateFlowObserver<StorageProviderSettings>
    @Environment(\.defernoColors) private var colors
    @Environment(\.openURL) private var openURL
    // The "Brain dump notifications" opt-in (#271): device-local, seeded once from the AppScope preference;
    // the toggle persists through the component and requests OS authorization on enable.
    @State private var brainDumpNotifications = false
    @State private var brainDumpNotificationsSeeded = false
    // The "keep brain-dump recordings" choice (#211): device-local, seeded once from the component (default on).
    @State private var keepRecordings = true
    @State private var keepRecordingsSeeded = false
    // The legal page presented in-app via a WKWebView that hides the site nav/footer (nil = none).
    // Compliant in-app presentation of our hosted Terms/Privacy — no link-stripping needed (Apple
    // 3.1.1 is about external purchase flows, not legal text).
    @State private var legalPage: LegalPage?
    // On-device data export (#313, ADR-0041): the Export/Full-backup action sheet, and the built Backup
    // zip handed to the iOS share sheet (nil = none). Replaces the old "export on the web" deep-link.
    @State private var showExportDialog = false
    @State private var exportFile: ExportFile?
    // On-device data import/restore (#314, ADR-0041): the document picker, and the outcome message shown
    // after the shared engine replays the Backup file's items as id-preserving creates on the outbox.
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
                                Text(L.string("settings_coming_soon_title")).font(.subheadline).foregroundStyle(colors.inkMuted)
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
        return List {
            Section(L.string("settings_appearance_theme_section")) {
                checkRow(L.string("common_app_name"), selected: value.themeFamily == ThemeFamily.deferno) { component.onThemeFamilyChanged(family: ThemeFamily.deferno) }
                checkRow(L.string("settings_theme_family_mono"), selected: value.themeFamily == ThemeFamily.mono) { component.onThemeFamilyChanged(family: ThemeFamily.mono) }
            }
            Section(L.string("settings_appearance_mode_section")) {
                checkRow(L.string("settings_theme_mode_light"), selected: value.themeMode == ThemeMode.light) { component.onThemeModeChanged(mode: ThemeMode.light) }
                checkRow(L.string("settings_theme_mode_dark"), selected: value.themeMode == ThemeMode.dark) { component.onThemeModeChanged(mode: ThemeMode.dark) }
                checkRow(L.string("settings_theme_mode_follow_system"), selected: value.themeMode == ThemeMode.auto) { component.onThemeModeChanged(mode: ThemeMode.auto) }
            }
        }
    }

    private var taskBehaviorDetail: some View {
        let value = settings.value
        return List {
            Section {
                Toggle(isOn: Binding(get: { value.dragAndDropEnabled }, set: { component.onDragAndDropChanged(enabled: $0) })) {
                    Text(L.string("settings_task_behavior_drag_drop_label")).foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            } footer: {
                Text(L.string("settings_task_behavior_drag_drop_description"))
            }
            Section(L.string("settings_show_done_everywhere_label")) {
                doneVisibilityPicker(current: Int(ShellBridgeKt.doneVisibilityGlobalSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setGlobalDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
            Section(L.string("settings_show_done_dashboard_label")) {
                doneVisibilityPicker(current: Int(ShellBridgeKt.doneVisibilityDashboardSeconds(settings: value))) { seconds in
                    ShellBridgeKt.setDashboardDoneVisibility(component: component, settings: value, seconds: Int64(seconds))
                }
            }
        }
    }

    private var speechDetail: some View {
        List {
            Section {
                Text(L.string("settings_speech_none_body"))
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
            } header: {
                Text(L.string("settings_speech_engine_label"))
            } footer: {
                Text(L.string("settings_agent_intro"))
            }
            Section {
                Toggle(isOn: Binding(get: { brainDumpNotifications }, set: { setBrainDumpNotifications($0) })) {
                    Text(L.string("settings_notify_braindump_label")).foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            } header: {
                Text(L.string("settings_notifications_section"))
            } footer: {
                Text(L.string("settings_notify_braindump_description"))
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
                    Text(L.string("settings_assistant_enable_label")).foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
                .disabled(value.busy)
            } header: {
                Text(L.string("settings_category_assistant"))
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
                    Text(L.string("settings_privacy_analytics_label")).foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            }
            Section {
                Button(L.string("settings_data_export_web_button")) { showExportDialog = true }
                    .listRowBackground(colors.surfaceCard)
            } footer: {
                Text(L.string("settings_data_export_import_description"))
            }
            .confirmationDialog(L.string("settings_data_your_data_section"), isPresented: $showExportDialog, titleVisibility: .visible) {
                Button(L.string("settings_data_export_menu_export")) { runExport() }
                Button(L.string("settings_data_export_menu_full_backup")) {}.disabled(true)
                Button(L.string("settings_import_button")) { showImportPicker = true }
                Button(L.string("common_cancel"), role: .cancel) {}
            }
        }
        .sheet(item: $exportFile) { file in
            ShareSheet(activityItems: [file.url])
        }
        // Import (#314): the document picker accepts a Backup-file zip; its bytes cross to the shared engine.
        .fileImporter(isPresented: $showImportPicker, allowedContentTypes: [.zip], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first { runImport(url) }
        }
        .alert(L.string("common_import"), isPresented: $showImportResult) {
            Button(L.string("common_ok"), role: .cancel) {}
        } message: {
            Text(importResultText)
        }
    }

    /// The Storage detail (#211): on-device usage (kept brain-dump recordings + total, largest first), a
    /// read-only storage-provider read-out (On-device active; the rest coming later), and the keep-recordings
    /// toggle. Offline-first — every figure comes from the on-device store, never the network.
    private var storageDetail: some View {
        let usage = storage.value
        return List {
            Section {
                HStack {
                    Text(L.string("settings_storage_recordings_section")).foregroundStyle(colors.onSurface)
                    Spacer()
                    Text(storageSummary(count: Int(usage.count), bytes: usage.totalBytes))
                        .foregroundStyle(colors.inkMuted)
                }
                .listRowBackground(colors.surfaceCard)
                ForEach(usage.recordings, id: \.id) { rec in
                    Button {
                        component.onOpenRecording(taskId: rec.taskId)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L.string("braindump_recording_status")).foregroundStyle(colors.onSurface)
                                Text(recordingDate(rec.createdAtEpochMs)).font(.caption).foregroundStyle(colors.inkMuted)
                            }
                            Spacer()
                            Text(formatBytes(rec.sizeBytes)).foregroundStyle(colors.inkMuted)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
                        }
                        .contentShape(Rectangle())
                    }
                    .listRowBackground(colors.surfaceCard)
                }
            } header: {
                Text(L.string("settings_storage_on_device_section"))
            } footer: {
                Text(L.string("settings_storage_recordings_description"))
            }

            // Logical attachments across ALL items (#311) — distinct from the on-device bytes above:
            // backend-hosted attachments occupy no device storage, so this deep-links into Search,
            // filtered to items with attachments and sorted biggest-first.
            Section {
                Button {
                    component.onOpenBiggestAttachments()
                } label: {
                    HStack {
                        Text(L.string("search_sort_biggest_attachments")).foregroundStyle(colors.onSurface)
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
                    }
                    .contentShape(Rectangle())
                }
                .listRowBackground(colors.surfaceCard)
            } header: {
                Text(L.string("settings_storage_all_items_section"))
            } footer: {
                Text(L.string("settings_storage_all_items_description"))
            }

            Section {
                labeledRow(L.string("settings_storage_provider_label"), ShellBridgeKt.storageActiveProviderName(state: provider.value))
                comingLaterRow(L.string("settings_storage_provider_deferno_backend"))
                comingLaterRow(L.string("settings_storage_provider_dropbox"))
                comingLaterRow(L.string("settings_storage_provider_google_drive"))
            } header: {
                Text(L.string("settings_storage_provider_label"))
            } footer: {
                Text(L.string("settings_storage_ios_note"))
            }

            Section {
                Toggle(isOn: Binding(get: { keepRecordings }, set: { setKeepRecordings($0) })) {
                    Text(L.string("settings_storage_keep_recordings_label")).foregroundStyle(colors.onSurface)
                }
                .listRowBackground(colors.surfaceCard)
            } header: {
                Text(L.string("braindump_title"))
            } footer: {
                Text(L.string("settings_storage_keep_recordings_description"))
            }
        }
        .onAppear {
            guard !keepRecordingsSeeded else { return }
            keepRecordingsSeeded = true
            keepRecordings = ShellBridgeKt.keepBrainDumpRecordingsEnabled(component: component)
        }
    }

    /// Persist the keep-recordings choice (#211) — device-local, never synced.
    private func setKeepRecordings(_ on: Bool) {
        keepRecordings = on
        ShellBridgeKt.setKeepBrainDumpRecordings(component: component, enabled: on)
    }

    /// Build the on-device Backup zip on the shared side (#313, ADR-0041), write it to a temp `.zip`, then
    /// present the iOS share sheet — share sheets share file URLs, not raw bytes. The bridge calls back on
    /// the main thread, so mutating `exportFile` here is safe.
    private func runExport() {
        ShellBridgeKt.exportBackup(component: component) { nsData in
            guard let nsData else { return }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("deferno-backup.zip")
            do {
                try (nsData as Data).write(to: url, options: .atomic)
                exportFile = ExportFile(url: url)
            } catch {
                // best-effort; nothing to share on failure
            }
        }
    }

    /// Restore items from a picked Backup file (#314, ADR-0041): read the security-scoped file's bytes (the
    /// same start/stop-access bracket as Feedback attachments), hand them to the shared engine, and show the
    /// outcome. The bridge reports `(kind, count)` on the main thread, so mutating the alert state is safe.
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

    private var legalDetail: some View {
        List {
            Section {
                Button(L.string("settings_legal_terms_section")) { legalPage = LegalPage(title: L.string("settings_legal_terms_section"), url: Self.termsURL) }
                    .foregroundStyle(colors.onSurface).listRowBackground(colors.surfaceCard)
                Button(L.string("settings_legal_privacy_policy_title")) { legalPage = LegalPage(title: L.string("settings_legal_privacy_policy_title"), url: Self.privacyURL) }
                    .foregroundStyle(colors.onSurface).listRowBackground(colors.surfaceCard)
            } footer: {
                Text(L.string("settings_legal_open_source_apache_body"))
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
                        Button(L.string("calendar_action_done")) { legalPage = nil }
                    }
                }
            }
        }
    }

    private static let termsURL = URL(string: "https://www.defernowork.com/terms")!
    private static let privacyURL = URL(string: "https://www.defernowork.com/privacy")!

    /// The Account category is an **account switcher** (#NN): the signed-in roster — tap the active account
    /// (chevron) to open its Profile (where identity + sign-out live), tap another to switch to it — plus
    /// "Add another account" (re-enters sign-in, keeping the others). Time zone moved to Profile too.
    private var accountDetail: some View {
        let active = ShellBridgeKt.settingsActiveAccountKey(component: component)
        return List {
            Section {
                ForEach(ShellBridgeKt.settingsAccounts(component: component)) { account in
                    let isActive = ShellBridgeKt.accountKey(account: account) == active
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
                        .contentShape(Rectangle())
                    }
                    .listRowBackground(colors.surfaceCard)
                    .accessibilityAddTraits(isActive ? [.isSelected] : [])
                }
            } header: {
                Text(L.string("shell_signed_in"))
            } footer: {
                Text(L.string("settings_accounts_intro"))
            }
            Section {
                Button { component.onAddAccount() } label: {
                    Label(L.string("auth_add_another_account"), systemImage: "plus")
                }
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
                Text(L.string("settings_coming_soon_generic_body"))
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
        let options: [(String, Int)] = [
            (L.string("settings_done_visibility_one_day"), 86400),
            (L.string("settings_done_visibility_three_days"), 259200),
            (L.string("settings_done_visibility_one_week"), 604800),
            (L.string("settings_done_visibility_always"), -1),
        ]
        return Picker(L.string("settings_show_for_label"), selection: Binding(get: { current }, set: { onSelect($0) })) {
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

    /// A muted, non-tappable provider row marked "Coming later" (the Storage read-out's roadmap, #211).
    private func comingLaterRow(_ label: String) -> some View {
        HStack {
            Text(label).foregroundStyle(colors.inkMuted)
            Spacer()
            Text(L.string("settings_storage_note_coming_later")).font(.subheadline).foregroundStyle(colors.inkMuted)
        }
        .listRowBackground(colors.surfaceCard)
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

/// A legal page to present in-app. Identifiable so it can drive `.sheet(item:)` (URL isn't).
private struct LegalPage: Identifiable {
    let title: String
    let url: URL
    var id: String { url.absoluteString }
}

/// A built Backup file (#313) to present via `.sheet(item:)` — a temp `.zip` URL. Identifiable for the sheet.
private struct ExportFile: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

/// Minimal `UIActivityViewController` wrapper for the iOS share sheet (#313, ADR-0041) — the same
/// `UIViewControllerRepresentable` UIKit-interop idiom as `LegalWebView`'s `WKWebView` below.
private struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
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
        URLQueryItem(name: "subject", value: L.string("settings_legal_account_removal_email_subject")),
        URLQueryItem(name: "body", value: L.string("settings_legal_account_removal_email_body")),
    ]
    return components.url
}
