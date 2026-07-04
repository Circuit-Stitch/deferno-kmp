import Deferno
import Foundation

/// Localized strings for the native macOS SwiftUI surfaces (#327), backed by `Localizable.xcstrings`.
/// The 5-locale Compose catalog (en/es/de/hi/pt) is the source of truth; its subset the bridges carry is
/// ported here. The shell hands Swift **typed** state (ADR-0003): the bridge accessors return stable
/// enum-name tokens (the Kotlin twins — `ChromeTitle`, `NewStatus.FailedReason`,
/// `FeedbackResult.Failed.Reason`, `AssistantError`, `InferenceFailureReason`), and these helpers map each
/// token to a catalog key. Server-authored prose (feedback / assistant `ServerMessage`) renders verbatim.
enum L {

    /// A localized string by catalog key.
    static func string(_ key: String) -> String {
        Bundle.main.localizedString(forKey: key, value: key, table: nil)
    }

    /// A localized format string filled with positional args (`%@`, `%lld`).
    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: string(key), locale: .current, arguments: args)
    }

    /// A localized, plural-agreed count string (backed by the catalog's `plural` variations).
    static func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(string(key), count)
    }


    /// A localized Item-kind label from its bridge enum name ("Task" → common_kind_task).
    static func kindLabel(_ name: String) -> String {
        switch name {
        case "Task": return string("common_kind_task")
        case "Chore": return string("common_kind_chore")
        case "Habit": return string("common_kind_habit")
        case "Event": return string("common_kind_event")
        default: return name
        }
    }

    // MARK: Chrome title (typed ChromeTitle)

    /// The top-bar title for a `ChromeSpec`: user-authored text renders verbatim; a Destination /
    /// Settings-category screen name and the Task-detail fallback localize; no title → empty.
    static func chromeTitle(_ spec: ChromeSpec) -> String {
        if let verbatim = ShellBridgeKt.chromeTitleVerbatim(spec: spec) { return verbatim }
        if let destination = ShellBridgeKt.chromeTitleDestination(spec: spec) {
            return destinationLabel(ShellBridgeKt.destinationName(destination: destination))
        }
        if let category = ShellBridgeKt.chromeTitleSettingsCategory(spec: spec) {
            return settingsCategoryLabel(ShellBridgeKt.settingsCategoryName(category: category))
        }
        if ShellBridgeKt.chromeTitleIsTaskFallback(spec: spec) { return string("common_kind_task") }
        return ""
    }

    /// A Destination's nav/chrome label, keyed off its stable enum name ("Plan" → shell_destination_plan).
    static func destinationLabel(_ name: String) -> String {
        string("shell_destination_" + name.lowercased())
    }

    /// A Settings category's chrome label, keyed off its stable enum name.
    static func settingsCategoryLabel(_ name: String) -> String {
        let key: String
        switch name {
        case "Appearance": key = "settings_category_appearance"
        case "TaskBehavior": key = "settings_category_task_behavior"
        case "SpeechEngine": key = "settings_category_speech_engine"
        case "Agent": key = "settings_category_agent"
        case "Assistant": key = "settings_category_assistant"
        case "Storage": key = "settings_category_storage"
        case "DataPrivacy": key = "settings_category_data_privacy"
        case "HelpFeedback": key = "settings_category_help_feedback"
        case "AppPermissions": key = "settings_category_app_permissions"
        case "Legal": key = "settings_category_legal"
        case "Account": key = "settings_category_account"
        case "Security2FA": key = "settings_category_security_2fa"
        case "Integrations": key = "settings_category_integrations"
        default: key = "shell_destination_settings"
        }
        return string(key)
    }

    // MARK: New / Feedback failure (typed reasons)

    /// The localized create-failure note, or nil when New isn't in a Failed state.
    static func newFailure(_ state: NewState) -> String? {
        guard let reason = ShellBridgeKt.doNewStatusFailedReason(state: state) else { return nil }
        switch reason {
        case "CouldNotSave": return string("new_error_could_not_save")
        default: return string("new_error_could_not_save_retry")
        }
    }

    /// The localized send-failure note, or nil when Feedback isn't in a Failed state. The server-authored
    /// `ServerMessage` arm renders verbatim; upload/send codes fill their format.
    static func feedbackFailure(_ state: FeedbackState) -> String? {
        guard let reason = ShellBridgeKt.feedbackStatusFailedReason(state: state) else { return nil }
        let code = Int(ShellBridgeKt.feedbackStatusFailedStatusCode(state: state))
        switch reason {
        case "PrepareAttachments": return string("feedback_error_presign_failed")
        case "UploadFailed": return format("feedback_error_upload_failed", code)
        case "SendFailed": return format("feedback_error_send_failed", code)
        case "AppOutOfDate": return string("common_error_app_out_of_date")
        default: return ShellBridgeKt.feedbackStatusFailedMessage(state: state)
        }
    }

    // MARK: Assistant error (typed AssistantError)

    /// The localized turn-error banner, or nil when there's no error. The server `ServerMessage` arm
    /// renders verbatim.
    static func assistantError(_ state: AssistantState) -> String? {
        guard let kind = ShellBridgeKt.assistantErrorKind(state: state) else { return nil }
        switch kind {
        case "TurnFailed": return string("assistant_error_turn_failed")
        case "EnableFailed": return string("assistant_error_enable_failed")
        case "ApplyFailed": return string("assistant_error_apply_failed")
        default: return ShellBridgeKt.assistantErrorServerMessage(state: state)
        }
    }

    // MARK: Move / undo (typed MoveOperation)

    /// The localized move-operation noun ("reorder" / "indent" / "outdent" token → localized word).
    static func moveOperation(_ token: String) -> String {
        switch token {
        case "reorder": return string("tasks_move_operation_reorder")
        case "indent": return string("tasks_move_operation_indent")
        case "outdent": return string("tasks_move_operation_outdent")
        default: return token
        }
    }

    // MARK: Draft-extract failure (typed InferenceResult.Failure reason, #327)

    /// The localized draft-extraction failure note for a typed `InferenceFailureReason` token. The raw
    /// `detail` is a content-free log string — kept out of the UI (it stays for logs only).
    static func draftExtractError(_ reason: String) -> String {
        switch reason {
        case "NotConfigured": return string("draft_extract_error_not_configured")
        case "MalformedOutput": return string("draft_extract_error_malformed")
        default: return string("draft_extract_error_transport")
        }
    }
}
